package io.github.kurukurumi33.mcp.backends.spark;

import io.github.kurukurumi33.mcp.backends.sql.SqlGuard;
import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A read-only SQL backend that runs each query as a {@code spark-submit} subprocess.
 *
 * <p>Spark cannot be embedded in the gateway. It brings its own Guava, Netty and Jackson, and those
 * versions collide with the gateway's; a shared JVM would fail at class-load time or, worse, at the
 * first query. A subprocess also buys the one thing a JDBC driver cannot give: when the gateway's
 * deadline expires it can kill the whole process tree instead of leaving an abandoned query running.
 *
 * <p>The price is startup cost. Every call pays a JVM start and a Spark session, which is seconds,
 * not milliseconds. Two consequences are deliberate:
 * <ul>
 *   <li>{@code max-parallel-jobs} defaults to 1. The gateway's bulkhead limits callers; this limit
 *       protects the machine from running several Spark drivers at once. Requests beyond it fail as
 *       a tool error, because the right retry is "wait for the backend", not "reschedule the request".</li>
 *   <li>{@code health()} only checks that the executable and the job script exist. A real check
 *       would start a JVM, and health runs far too often for that.</li>
 * </ul>
 *
 * <p>The statement passes the same {@link SqlGuard} as the DuckDB backend: one read-only statement,
 * literals and comments stripped before the keyword check. Spark SQL can still read files by path
 * and run functions with side effects, so this protects against a model writing the wrong thing,
 * not against a hostile caller.
 */
public final class SparkQueryAdapter implements BackendAdapter {

    /** Printed by the job script; everything else on the stream is Spark's own logging. */
    static final String RESULT_MARKER = "###SPARK_RESULT###";

    private static final String SCRIPT_RESOURCE =
            "/io/github/kurukurumi33/mcp/backends/spark/spark_query.py";

    private static final int DEFAULT_MAX_ROWS = 50;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_PARALLEL_JOBS = 1;
    private static final int HARD_MAX_ROWS = 500;
    private static final int ERROR_TAIL_LINES = 12;

    private final String namespace;
    private final Settings settings;
    private final Semaphore jobSlots;

    private final Object scriptLock = new Object();
    private Path script;

    /**
     * Everything the adapter needs that comes from configuration.
     *
     * @param sparkSubmit path to {@code spark-submit} (or {@code spark-submit.cmd} on Windows)
     * @param master      Spark master URL; {@code local[2]} by default
     * @param python      interpreter for PySpark, exported as {@code PYSPARK_PYTHON}; null inherits
     * @param sparkHome   exported as {@code SPARK_HOME}; null inherits
     * @param hadoopHome  exported as {@code HADOOP_HOME}, with its {@code bin} prepended to PATH;
     *                    Windows Spark refuses to start without winutils
     */
    public record Settings(Path sparkSubmit, String master, Path python, Path sparkHome,
                           Path hadoopHome, int maxRows, int timeoutSeconds, int maxParallelJobs) {

        public Settings {
            if (sparkSubmit == null) {
                throw new IllegalArgumentException("mcp.gateway.spark.submit must point at spark-submit");
            }
            master = master == null || master.isBlank() ? "local[2]" : master;
            maxRows = Math.max(1, Math.min(HARD_MAX_ROWS, maxRows));
            timeoutSeconds = Math.max(1, timeoutSeconds);
            maxParallelJobs = Math.max(1, maxParallelJobs);
        }

        /** Defaults for everything except the executable. */
        public static Settings of(Path sparkSubmit) {
            return new Settings(sparkSubmit, "local[2]", null, null, null,
                    DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_PARALLEL_JOBS);
        }
    }

    public SparkQueryAdapter(String namespace, Settings settings) {
        this.namespace = namespace;
        this.settings = settings;
        this.jobSlots = new Semaphore(settings.maxParallelJobs());
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String description() {
        return "Read-only SQL on Apache Spark (" + settings.master()
                + "), one spark-submit process per call";
    }

    @Override
    public List<ToolDefinition> tools() {
        ObjectNode schema = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("sql", Json.obj()
                                .put("type", "string")
                                .put("description", "One read-only Spark SQL statement. "
                                        + "The first call takes seconds: a Spark session is started."))
                        .set("limit", Json.obj()
                                .put("type", "integer")
                                .put("description", "Maximum rows to return, capped at "
                                        + settings.maxRows() + ".")))
                .set("required", Json.arr().add("sql"));

        return List.of(ToolDefinition.of("run_sql",
                "Runs one read-only Spark SQL query and returns columns plus rows. "
                        + "Use it for data that lives in the Spark catalog, not for writes.",
                schema));
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        if (!"run_sql".equals(toolName)) {
            return ToolResult.error("Unknown tool '" + toolName + "'");
        }
        return runSql(context);
    }

    private ToolResult runSql(RequestContext context) {
        String sql = context.stringArg("sql");
        Optional<String> rejection = SqlGuard.rejectUnsafe(sql);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        Integer requested = context.intArg("limit");
        int limit = requested == null
                ? settings.maxRows()
                : Math.max(1, Math.min(settings.maxRows(), requested));

        if (!jobSlots.tryAcquire()) {
            return ToolResult.error("The Spark backend is already running " + settings.maxParallelJobs()
                    + " job(s). Retry in a few seconds.");
        }

        Path sqlFile = null;
        try {
            Path jobScript = materializeScript();
            sqlFile = Files.createTempFile(jobScript.toAbsolutePath().getParent(),
                    "mcp-spark-", ".sql");
            Files.writeString(sqlFile, sql, StandardCharsets.UTF_8);

            Process process = builder(jobScript, sqlFile, limit).start();
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> drain(process, output));

            boolean finished;
            try {
                finished = process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                killTree(process);
                Thread.currentThread().interrupt();
                return ToolResult.error("Spark job was cancelled after the gateway stopped waiting");
            }

            if (!finished) {
                killTree(process);
                joinQuietly(reader);
                return ToolResult.error("Spark query exceeded " + settings.timeoutSeconds()
                        + " s; the job was killed");
            }
            joinQuietly(reader);

            String text = output.toString();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return ToolResult.error(failure(text, exitCode));
            }

            JsonNode payload = extractPayload(text);
            if (payload == null) {
                return ToolResult.error("Spark job finished without a result:\n" + tail(text));
            }
            return toToolResult(payload, limit);
        } catch (IOException e) {
            return ToolResult.error("Spark job could not be started: " + e.getMessage());
        } finally {
            jobSlots.release();
            if (sqlFile != null) {
                try {
                    Files.deleteIfExists(sqlFile);
                } catch (IOException ignored) {
                    // The temp directory will be cleaned eventually.
                }
            }
        }
    }

    // ------------------------------------------------------------------ process

    /**
     * Builds the command with file names only, never absolute paths.
     *
     * <p>Spark's Windows launcher re-encodes its arguments through the console code page, so any
     * non-ASCII character in the script path arrives at the JVM as {@code ?} and the launch dies in
     * {@code File.getCanonicalPath} with "Bad pathname". That includes the default temp directory
     * of anyone whose Windows user name is not ASCII. The working directory is set instead, which
     * Windows passes natively as Unicode, and the child resolves the names against it.
     */
    private ProcessBuilder builder(Path jobScript, Path sqlFile, int limit) {
        List<String> command = new ArrayList<>();
        if (needsCmdWrapper()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(settings.sparkSubmit().toString());
        command.add("--master");
        command.add(settings.master());
        command.add("--conf");
        command.add("spark.ui.enabled=false");
        command.add(jobScript.getFileName().toString());
        command.add(sqlFile.getFileName().toString());
        command.add(Integer.toString(limit));

        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(jobScript.toAbsolutePath().getParent().toFile());
        Map<String, String> environment = builder.environment();
        environment.put("PYTHONIOENCODING", "utf-8");
        if (settings.python() != null) {
            environment.put("PYSPARK_PYTHON", settings.python().toString());
        }
        if (settings.sparkHome() != null) {
            environment.put("SPARK_HOME", settings.sparkHome().toString());
        }
        if (settings.hadoopHome() != null) {
            environment.put("HADOOP_HOME", settings.hadoopHome().toString());
            // Spark looks for winutils next to HADOOP_HOME, but Hadoop's shell loader still wants
            // the bin directory on PATH on Windows.
            environment.merge("PATH",
                    settings.hadoopHome().resolve("bin").toString(),
                    (existing, bin) -> bin + File.pathSeparator + existing);
        }
        return builder;
    }

    private boolean needsCmdWrapper() {
        String name = settings.sparkSubmit().getFileName().toString().toLowerCase(Locale.ROOT);
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                && (name.endsWith(".cmd") || name.endsWith(".bat"));
    }

    private static void drain(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // The process was killed or closed its stream; whatever was read is still usable.
        }
    }

    /**
     * Kills the Spark driver and every JVM it started.
     *
     * <p>Destroying only the process we launched would leave the driver running on Windows: the
     * entry point is a {@code .cmd} wrapper that starts the JVM as a child, so killing the wrapper
     * orphans a full Spark driver. Descendants first, then the parent.
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ result

    static JsonNode extractPayload(String output) {
        String[] lines = output.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!RESULT_MARKER.equals(lines[i].trim())) {
                continue;
            }
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                try {
                    return Json.mapper().readTree(candidate);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ToolResult toToolResult(JsonNode payload, int limit) {
        JsonNode columns = payload.get("columns");
        JsonNode rows = payload.get("rows");
        if (columns == null || rows == null) {
            return ToolResult.error("Spark job returned a malformed result: " + payload);
        }
        JsonNode truncatedNode = payload.get("truncated");
        boolean truncated = truncatedNode != null && truncatedNode.asBoolean();

        int rowCount = rows.size();
        int columnCount = columns.size();

        ObjectNode structured = Json.obj()
                .set("columns", columns)
                .set("rows", rows)
                .put("rowCount", rowCount)
                .put("truncated", truncated);

        String summary = rowCount + " row(s), " + columnCount + " column(s)"
                + (truncated ? " — truncated at limit " + limit : "");
        return ToolResult.of(summary, structured);
    }

    /**
     * Spark logs several hundred lines of INFO before it fails, so a tail alone hides the reason.
     * The first exception line goes on top of the message; the tail stays for context.
     */
    private static String failure(String output, int exitCode) {
        String first = output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("Exception") || line.startsWith("ERROR")
                        || line.startsWith("Traceback") || line.startsWith("Caused by"))
                .findFirst()
                .orElse(null);
        return "Spark job failed (exit " + exitCode + ")"
                + (first == null ? "" : ": " + first)
                + "\n" + tail(output);
    }

    private static String tail(String output) {
        String[] lines = output.split("\\R");
        int from = Math.max(0, lines.length - ERROR_TAIL_LINES);
        return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
    }

    // ------------------------------------------------------------------ setup

    /**
     * Extracts the job script from the jar once and reuses it.
     *
     * <p>The script is a resource rather than a string constant so it stays a real Python file that
     * can be run by hand while debugging.
     */
    private Path materializeScript() throws IOException {
        synchronized (scriptLock) {
            if (script != null && Files.isRegularFile(script)) {
                return script;
            }
            Path extracted = Files.createTempFile("mcp-spark-job-", ".py");
            try (InputStream in = SparkQueryAdapter.class.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (in == null) {
                    throw new IOException("classpath resource " + SCRIPT_RESOURCE + " is missing");
                }
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            }
            extracted.toFile().deleteOnExit();
            script = extracted;
            return extracted;
        }
    }

    /**
     * Presence check only. Starting Spark to prove it works would cost seconds on every actuator
     * poll, so a broken installation shows up as failed calls instead of a DOWN backend.
     */
    @Override
    public HealthStatus health() {
        Path submit = settings.sparkSubmit();
        if (!Files.isRegularFile(submit)) {
            return HealthStatus.down("spark-submit not found: " + submit);
        }
        if (settings.python() != null && !Files.isRegularFile(settings.python())) {
            return HealthStatus.down("PYSPARK_PYTHON not found: " + settings.python());
        }
        try {
            materializeScript();
        } catch (IOException e) {
            return HealthStatus.down("Spark job script unavailable: " + e.getMessage());
        }
        return HealthStatus.UP;
    }
}