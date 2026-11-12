package io.github.kurukurumi33.mcp.backends.spark;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises everything around Spark with a fake spark-submit, so the tests stay fast and need no
 * installation. What they cannot prove is that a real Spark job returns these columns; that is what
 * the manual run in the README is for.
 */
class SparkQueryAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeStatementsAreRejectedBeforeAnyProcessStarts() {
        SparkQueryAdapter adapter = adapter(tempDir.resolve("missing-spark-submit"));

        ToolResult result = adapter.execute("run_sql", context("DROP TABLE orders"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("read-only"), text(result));
    }

    @Test
    void parsesTheLineAfterTheMarkerAndIgnoresSparkLogging() throws IOException {
        Path submit = fakeSparkSubmit("""
                echo 26/11/11 INFO SparkContext: running
                echo %s
                %s
                """.formatted(SparkQueryAdapter.RESULT_MARKER,
                echoJson("{\"columns\":[\"n\",\"s\"],\"rows\":[[1,\"x\"],[2,\"y\"]],\"truncated\":false}")));
        SparkQueryAdapter adapter = adapter(submit);

        ToolResult result = adapter.execute("run_sql", context("SELECT 1 AS n, 'x' AS s"));

        assertFalse(result.isError(), text(result));
        JsonNode structured = result.structuredContent();
        assertEquals(2, structured.get("columns").size());
        assertEquals(2, structured.get("rows").size());
        assertEquals(2, structured.get("rowCount").asInt());
        assertFalse(structured.get("truncated").asBoolean());
    }

    @Test
    void truncationReachesTheModel() throws IOException {
        Path submit = fakeSparkSubmit("""
                echo %s
                %s
                """.formatted(SparkQueryAdapter.RESULT_MARKER,
                echoJson("{\"columns\":[\"n\"],\"rows\":[[1]],\"truncated\":true}")));
        SparkQueryAdapter adapter = adapter(submit);

        ToolResult result = adapter.execute("run_sql", context("SELECT * FROM big"));

        assertFalse(result.isError(), text(result));
        assertTrue(text(result).contains("truncated"), text(result));
        assertTrue(result.structuredContent().get("truncated").asBoolean());
    }

    @Test
    void outputWithoutTheMarkerIsAnError() throws IOException {
        Path submit = fakeSparkSubmit("""
                echo Spark died before writing anything
                """);
        SparkQueryAdapter adapter = adapter(submit);

        ToolResult result = adapter.execute("run_sql", context("SELECT 1"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("without a result"), text(result));
    }

    @Test
    void aJobThatOutlivesTheDriverTimeoutIsKilled() throws IOException {
        Path submit = fakeSparkSubmit(sleepCommand());
        SparkQueryAdapter adapter = new SparkQueryAdapter("spark", new SparkQueryAdapter.Settings(
                submit, "local[2]", null, null, null, 10, 1, 1));

        long startedAt = System.nanoTime();
        ToolResult result = adapter.execute("run_sql", context("SELECT 1"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.isError());
        assertTrue(text(result).contains("exceeded"), text(result));
        assertTrue(elapsedMillis < 6_000, "timeout took " + elapsedMillis + " ms");
    }

    @Test
    void jobFilesArePassedAsBareNamesSoTheCommandLineStaysAscii() throws IOException {
        String body = windows() ? """
                set SPY=
                set SQY=
                for %%a in (%*) do if /i "%%~xa"==".py" set SPY=%%~a
                for %%a in (%*) do if /i "%%~xa"==".sql" set SQY=%%~a
                if not exist "%SPY%" exit /b 3
                if not exist "%SQY%" exit /b 3
                echo @MARKER@
                echo {"columns":["script","sql"],"rows":[["%SPY%","%SQY%"]],"truncated":false}
                """ : """
                SPY=
                SQY=
                for a in "$@"; do
                  case "$a" in
                    *.py) SPY=$a ;;
                    *.sql) SQY=$a ;;
                  esac
                done
                [ -f "$SPY" ] || exit 3
                [ -f "$SQY" ] || exit 3
                echo @MARKER@
                echo "{\\"columns\\":[\\"script\\",\\"sql\\"],\\"rows\\":[[\\"$SPY\\",\\"$SQY\\"]],\\"truncated\\":false}"
                """;
        SparkQueryAdapter adapter = adapter(fakeSparkSubmit(body.replace("@MARKER@",
                SparkQueryAdapter.RESULT_MARKER)));

        ToolResult result = adapter.execute("run_sql", context("SELECT 1"));

        assertFalse(result.isError(), text(result));
        JsonNode row = result.structuredContent().get("rows").get(0);
        String script = row.get(0).asString();
        String sql = row.get(1).asString();
        assertTrue(script.endsWith(".py"), script);
        assertTrue(sql.endsWith(".sql"), sql);
        assertFalse(script.contains("/") || script.contains("\\"), script);
        assertFalse(sql.contains("/") || sql.contains("\\"), sql);
    }

    @Test
    void healthReportsAMissingExecutableWithoutStartingAnything() {
        SparkQueryAdapter adapter = adapter(tempDir.resolve("not-installed"));

        assertFalse(adapter.health().isUp());
        assertTrue(adapter.health().detail().contains("not found"));
    }

    // ------------------------------------------------------------------ helpers

    private SparkQueryAdapter adapter(Path submit) {
        return new SparkQueryAdapter("spark", SparkQueryAdapter.Settings.of(submit));
    }

    private static RequestContext context(String sql) {
        ObjectNode arguments = Json.obj().put("sql", sql);
        return new RequestContext("spark_run_sql", arguments, null, null, null, null, null, null);
    }

    private static String text(ToolResult result) {
        return result.content().isEmpty() ? "" : result.content().get(0).get("text").asString();
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String sleepCommand() {
        return windows() ? "ping -n 8 127.0.0.1 >nul\n" : "sleep 7\n";
    }

    /**
     * POSIX shells strip the quotes inside an unquoted argument, so {@code echo {"a":"b"}} arrives
     * as {@code {a:b}} and the adapter cannot parse it. Single-quoting keeps the JSON intact;
     * cmd.exe has no such problem.
     */
    private static String echoJson(String json) {
        return windows() ? "echo " + json : "printf '%s\\n' '" + json + "'";
    }

    /**
     * A stand-in for spark-submit. Windows gets a .cmd because that is what the adapter has to wrap
     * in cmd.exe; other platforms get a shell script and run it directly.
     */
    private Path fakeSparkSubmit(String body) throws IOException {
        boolean windows = windows();
        Path file = tempDir.resolve(windows ? "spark-submit.cmd" : "spark-submit.sh");
        String content = windows
                ? "@echo off\r\n" + body.replace("\r\n", "\n").replace("\n", "\r\n")
                : "#!/bin/sh\n" + body;
        Files.writeString(file, content);
        if (!windows) {
            assertTrue(file.toFile().setExecutable(true));
        }
        return file;
    }
}