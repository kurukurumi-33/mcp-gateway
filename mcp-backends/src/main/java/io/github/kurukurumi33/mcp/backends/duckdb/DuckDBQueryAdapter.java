package io.github.kurukurumi33.mcp.backends.duckdb;

import io.github.kurukurumi33.mcp.backends.sql.SqlGuard;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A read-only SQL backend on top of DuckDB.
 *
 * <p>The point of this adapter is that a model can look at data, not just at objects. It stays
 * deliberately narrow: one statement per call, reads only, a hard row cap and a query timeout. The
 * gateway still owns everything protocol-shaped; this class only knows JDBC.
 *
 * <p>Three tools, in the order a model should use them: {@code list_tables} and
 * {@code describe_table} read {@code information_schema}, then {@code run_sql} runs the query the
 * model was able to write because it looked first. The catalog lives here rather than in its own
 * backend because an in-memory DuckDB database exists only on its connection: a second adapter
 * would have to share it, which would report one resource as two backends that fail together.
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
        ObjectNode listTablesSchema = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("schema", Json.obj()
                                .put("type", "string")
                                .put("description", "Schema to list. Defaults to 'main'.")));

        ObjectNode describeTableSchema = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("table", Json.obj()
                                .put("type", "string")
                                .put("description", "Table name, exactly as list_tables returned it."))
                        .set("schema", Json.obj()
                                .put("type", "string")
                                .put("description", "Schema the table lives in. Defaults to 'main'.")))
                .set("required", Json.arr().add("table"));

        ObjectNode runSqlSchema = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("sql", Json.obj()
                                .put("type", "string")
                                .put("description", "One read-only SQL statement. No semicolon-separated batch."))
                        .set("limit", Json.obj()
                                .put("type", "integer")
                                .put("description", "Maximum rows to return, capped at " + maxRows + ".")))
                .set("required", Json.arr().add("sql"));

        return List.of(
                ToolDefinition.of("list_tables",
                        "Lists the tables and views in a schema. Call this first: it is the only way "
                                + "to learn what data exists before writing SQL.", listTablesSchema),
                ToolDefinition.of("describe_table",
                        "Returns the columns of one table with their types, nullability and defaults. "
                                + "Use it when the query depends on exact column names.", describeTableSchema),
                ToolDefinition.of("run_sql",
                        "Runs one read-only SQL query and returns columns plus rows. "
                                + "Use it to inspect data that already exists; writes are rejected.",
                        runSqlSchema));
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        return switch (toolName) {
            case "run_sql" -> runSql(context);
            case "list_tables" -> listTables(context);
            case "describe_table" -> describeTable(context);
            default -> ToolResult.error("Unknown tool '" + toolName + "'");
        };
    }

    private ToolResult runSql(RequestContext context) {

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

    /**
     * Lists what exists. Both catalog tools pass the argument as a bound parameter rather than
     * pasting it into the statement, so a schema or table name is always data: there is no string
     * in this class that a caller can close and escape from.
     */
    private ToolResult listTables(RequestContext context) {
        String schema = schemaArg(context);
        try {
            return withConnection(connection -> {
                ArrayNode tables = Json.arr();
                String sql = "SELECT table_schema, table_name, table_type"
                        + " FROM information_schema.tables"
                        + " WHERE lower(table_schema) = lower(?)"
                        + " ORDER BY table_name LIMIT " + maxRows;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, schema);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            tables.add(Json.obj()
                                    .put("schema", rs.getString("table_schema"))
                                    .put("name", rs.getString("table_name"))
                                    .put("type", rs.getString("table_type")));
                        }
                    }
                }

                ObjectNode structured = Json.obj()
                        .put("schema", schema)
                        .set("tables", tables)
                        .put("count", tables.size());
                return ToolResult.of(tables.size() + " table(s) in schema '" + schema + "'", structured);
            });
        } catch (SQLException e) {
            return ToolResult.error("Catalog error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Catalog failed: " + e.getMessage());
        }
    }

    /** Describes one table. A name that matches nothing is a failed call with a next step in it. */
    private ToolResult describeTable(RequestContext context) {
        String table = context.stringArg("table");
        if (table == null || table.isBlank()) {
            return ToolResult.error("table is required");
        }
        String schema = schemaArg(context);
        try {
            return withConnection(connection -> {
                ArrayNode columns = Json.arr();
                String sql = "SELECT column_name, data_type, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE lower(table_schema) = lower(?) AND lower(table_name) = lower(?)"
                        + " ORDER BY ordinal_position";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, schema);
                    statement.setString(2, table.trim());
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            ObjectNode column = Json.obj()
                                    .put("name", rs.getString("column_name"))
                                    .put("type", rs.getString("data_type"))
                                    .put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                            String defaultValue = rs.getString("column_default");
                            if (defaultValue != null) {
                                column.put("default", defaultValue);
                            }
                            columns.add(column);
                        }
                    }
                }

                if (columns.isEmpty()) {
                    // Naming the next tool is cheaper than letting the model guess at a table that
                    // does not exist and read a driver error instead.
                    return ToolResult.error("Table '" + table + "' not found in schema '" + schema
                            + "'. Call list_tables to see what exists.");
                }

                ObjectNode structured = Json.obj()
                        .put("schema", schema)
                        .put("table", table.trim())
                        .set("columns", columns)
                        .put("columnCount", columns.size());
                return ToolResult.of(columns.size() + " column(s) in '" + table.trim() + "'", structured);
            });
        } catch (SQLException e) {
            return ToolResult.error("Catalog error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Catalog failed: " + e.getMessage());
        }
    }

    private static String schemaArg(RequestContext context) {
        String schema = context.stringArg("schema");
        return schema == null || schema.isBlank() ? "main" : schema.trim();
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
        return SqlGuard.rejectUnsafe(sql);
    }

    static String stripLiteralsAndComments(String sql) {
        return SqlGuard.stripLiteralsAndComments(sql);
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