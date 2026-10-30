package io.github.kurukurumi33.mcp.backends.duckdb;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only SQL backend on top of DuckDB.
 *
 * <p>The point of this adapter is that a model can look at data, not just at objects. It stays
 * deliberately narrow: one statement per call, reads only, a hard row cap and a query timeout. The
 * gateway still owns everything protocol-shaped; this class only knows JDBC.
 *
 * <p>Three safety layers, because a single one is easy to get wrong:
 * <ol>
 *   <li>the statement must start with a read-only keyword, with string literals and comments
 *       stripped before the check so {@code SELECT 'drop table x'} is not mistaken for a write;</li>
 *   <li>a second statement separator is rejected, so {@code SELECT 1; DROP TABLE t} never reaches
 *       the driver;</li>
 *   <li>the result set is capped with {@code setMaxRows}, so a {@code SELECT *} on a large table
 *       cannot pull the whole thing into the gateway's heap.</li>
 * </ol>
 *
 * <p>Connections: an in-memory URL ({@code jdbc:duckdb:}) keeps one connection open for the life of
 * the adapter — an in-memory DuckDB database disappears when its last connection closes — and calls
 * are serialized on a lock, because a JDBC connection is not thread-safe. A file URL opens a fresh
 * connection per call, which lets concurrent readers run in parallel.
 *
 * <p>The schema has to exist before a query can run. For in-memory databases the optional
 * {@code initSql} is executed once, when the connection is first opened; for file databases it is
 * ignored, so a restart never re-seeds production data.
 */
public final class DuckDBQueryAdapter implements BackendAdapter, AutoCloseable {

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int HARD_MAX_ROWS = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    /** Statements that cannot change anything. {@code DESC} is shorthand for {@code DESCRIBE}. */
    private static final Set<String> READ_ONLY_LEADERS = Set.of(
            "select", "with", "from", "values", "describe", "desc", "show", "explain", "summarize");

    /**
     * Anything that can write, attach another database, install an extension or move files. Checked
     * as whole words on the stripped statement, so a column called {@code updated_at} is fine.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "merge", "upsert", "drop", "alter", "create", "truncate",
            "attach", "detach", "copy", "export", "import", "install", "load", "pragma",
            "set", "reset", "call", "vacuum", "checkpoint", "begin", "commit", "rollback");

    private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_]*");

    private final String namespace;
    private final String jdbcUrl;
    private final int maxRows;
    private final int timeoutSeconds;
    private final List<String> initSql;
    private final boolean inMemory;

    private final ReentrantLock lock = new ReentrantLock();
    private Connection shared;

    public DuckDBQueryAdapter(String namespace, String jdbcUrl) {
        this(namespace, jdbcUrl, DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT_SECONDS, List.of());
    }

    public DuckDBQueryAdapter(String namespace, String jdbcUrl, int maxRows, int timeoutSeconds,
                              List<String> initSql) {
        this.namespace = namespace;
        this.jdbcUrl = jdbcUrl == null || jdbcUrl.isBlank() ? "jdbc:duckdb:" : jdbcUrl;
        this.maxRows = Math.max(1, Math.min(HARD_MAX_ROWS, maxRows));
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.initSql = initSql == null ? List.of() : List.copyOf(initSql);
        this.inMemory = this.jdbcUrl.equals("jdbc:duckdb:")
                || this.jdbcUrl.startsWith("jdbc:duckdb::memory:");
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String description() {
        return "Read-only SQL over DuckDB (" + (inMemory ? "in-memory" : jdbcUrl) + ")";
    }

    @Override
    public List<ToolDefinition> tools() {
        ObjectNode schema = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("sql", Json.obj()
                                .put("type", "string")
                                .put("description", "One read-only SQL statement. No semicolon-separated batch."))
                        .set("limit", Json.obj()
                                .put("type", "integer")
                                .put("description", "Maximum rows to return, capped at " + maxRows + ".")))
                .set("required", Json.arr().add("sql"));

        return List.of(ToolDefinition.of(
                "run_sql",
                "Runs one read-only SQL query and returns columns plus rows. "
                        + "Use it to inspect data that already exists; writes are rejected.",
                schema));
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        if (!"run_sql".equals(toolName)) {
            return ToolResult.error("Unknown tool '" + toolName + "'");
        }

        String sql = context.stringArg("sql");
        if (sql == null || sql.isBlank()) {
            return ToolResult.error("sql is required");
        }
        Optional<String> rejection = rejectUnsafe(sql);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        Integer requested = context.intArg("limit");
        int limit = requested == null ? maxRows : Math.max(1, Math.min(maxRows, requested));

        try {
            return withConnection(connection -> runQuery(connection, sql, limit));
        } catch (SQLException e) {
            // A broken query is a failed tool call, not a broken protocol: the model gets isError
            // and can decide to fix the SQL rather than the gateway aborting the request.
            return ToolResult.error("SQL error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Query failed: " + e.getMessage());
        }
    }

    @Override
    public HealthStatus health() {
        try {
            withConnection(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet ignored = statement.executeQuery("SELECT 1")) {
                    ignored.next();
                    return null;
                }
            });
            return HealthStatus.UP;
        } catch (SQLException | RuntimeException e) {
            return HealthStatus.down("DuckDB unreachable: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (shared != null) {
                shared.close();
                shared = null;
            }
        } catch (SQLException ignored) {
            // Nothing useful to do while shutting down.
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ execution

    private ToolResult runQuery(Connection connection, String sql, int limit) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Ask for one row more than allowed: that extra row is how truncation is detected.
            statement.setMaxRows(limit + 1);
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignored) {
                // The driver declines; the row cap still bounds the response.
            }

            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                ArrayNode columns = Json.arr();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                ArrayNode rows = Json.arr();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == limit) {
                        truncated = true;
                        break;
                    }
                    ArrayNode row = Json.arr();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(toJson(rs.getObject(i)));
                    }
                    rows.add(row);
                }

                ObjectNode structured = Json.obj()
                        .set("columns", columns)
                        .set("rows", rows)
                        .put("rowCount", rows.size())
                        .put("truncated", truncated);

                String summary = rows.size() + " row(s), " + columnCount + " column(s)"
                        + (truncated ? " — truncated at limit " + limit : "");
                return ToolResult.of(summary, structured);
            }
        }
    }

    private static JsonNode toJson(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        // Dates and timestamps would become epoch numbers under the default mapper, which is
        // technically valid JSON but unreadable to a model. Keep them as text.
        if (value instanceof java.util.Date date) {
            return JsonNodeFactory.instance.textNode(date.toInstant().toString());
        }
        if (value instanceof TemporalAccessor temporal) {
            return JsonNodeFactory.instance.textNode(temporal.toString());
        }
        try {
            return Json.mapper().valueToTree(value);
        } catch (RuntimeException e) {
            return JsonNodeFactory.instance.textNode(String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------ safety

    /**
     * Returns a human-readable reason when the statement may write, attach or smuggle in a second
     * statement. Empty means it passed every check.
     */
    static Optional<String> rejectUnsafe(String sql) {
        String stripped = stripLiteralsAndComments(sql).trim();
        if (stripped.isEmpty()) {
            return Optional.of("sql is empty");
        }

        String withoutTrailing = stripped.endsWith(";")
                ? stripped.substring(0, stripped.length() - 1).trim()
                : stripped;
        if (withoutTrailing.indexOf(';') >= 0) {
            return Optional.of("Only one statement per call is allowed");
        }

        String normalized = withoutTrailing.toLowerCase(Locale.ROOT);
        String leader = firstWord(normalized);
        if (!READ_ONLY_LEADERS.contains(leader)) {
            return Optional.of("Only read-only statements are allowed (SELECT, WITH, DESCRIBE, SHOW, EXPLAIN); got '"
                    + leader + "'");
        }

        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            if (FORBIDDEN.contains(words.group())) {
                return Optional.of("Statement contains '" + words.group()
                        + "', which this backend does not allow");
            }
        }
        return Optional.empty();
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(0, end);
    }

    /**
     * Replaces string literals, quoted identifiers and comments with a space.
     *
     * <p>Without this, {@code SELECT 'delete' AS word} would be rejected for a word that never
     * reaches the parser as a keyword.
     */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        // Two quotes in a row are an escaped quote, not the end of the literal.
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '$') {
                int end = dollarQuoteEnd(sql, i);
                if (end > 0) {
                    i = end;
                    out.append(' ');
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Returns the index just past a {@code $tag$...$tag$} literal, or -1 when there is none. */
    private static int dollarQuoteEnd(String sql, int start) {
        int tagEnd = sql.indexOf('$', start + 1);
        if (tagEnd < 0) {
            return -1;
        }
        String tag = sql.substring(start, tagEnd + 1);
        int closing = sql.indexOf(tag, tagEnd + 1);
        return closing < 0 ? -1 : closing + tag.length();
    }

    // ------------------------------------------------------------------ connections

    private <T> T withConnection(SqlWork<T> work) throws SQLException {
        if (!inMemory) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                return work.run(connection);
            }
        }

        lock.lock();
        try {
            if (shared == null || shared.isClosed()) {
                shared = DriverManager.getConnection(jdbcUrl);
                initialize(shared);
            }
            return work.run(shared);
        } finally {
            lock.unlock();
        }
    }

    private void initialize(Connection connection) throws SQLException {
        if (initSql.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            for (String ddl : initSql) {
                statement.execute(ddl);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}