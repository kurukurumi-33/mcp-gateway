package io.github.kurukurumi33.mcp.backends.duckdb;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.StreamEmitter;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the read-only guard and the result shaping. Runs entirely in memory, so the build needs
 * no database service.
 */
class DuckDBQueryAdapterTest {

    private static final List<String> SEED = List.of(
            "CREATE TABLE orders (id INTEGER, customer VARCHAR, amount DECIMAL(10,2))",
            "INSERT INTO orders VALUES (1,'alice',10.50),(2,'bob',20.00),(3,'carol',30.25)");

    private DuckDBQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DuckDBQueryAdapter("analytics", "jdbc:duckdb:", 200, 5, SEED);
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    @Test
    @DisplayName("tools() describes the catalog without opening a database")
    void catalogNeedsNoConnection() {
        var offline = new DuckDBQueryAdapter("analytics", "jdbc:duckdb:C:/no/such/directory/x.duckdb");
        assertEquals(1, offline.tools().size());
        assertEquals("run_sql", offline.tools().get(0).name());
    }

    @Test
    @DisplayName("a SELECT returns column names and rows")
    void selectReturnsRows() {
        ToolResult result = adapter.execute("run_sql",
                context(Json.obj().put("sql", "SELECT id, customer FROM orders ORDER BY id")));

        JsonNode data = structured(result);
        assertEquals(3, data.path("rowCount").asInt());
        assertEquals("id", data.path("columns").get(0).asString());
        assertEquals("customer", data.path("columns").get(1).asString());
        assertEquals(1, data.path("rows").get(0).get(0).asInt());
        assertEquals("alice", data.path("rows").get(0).get(1).asString());
        assertFalse(data.path("truncated").asBoolean());
    }

    @Test
    @DisplayName("limit caps the rows and flags the result as truncated")
    void limitTruncates() {
        ToolResult result = adapter.execute("run_sql", context(Json.obj()
                .put("sql", "SELECT * FROM orders ORDER BY id")
                .put("limit", 2)));

        JsonNode data = structured(result);
        assertEquals(2, data.path("rowCount").asInt());
        assertTrue(data.path("truncated").asBoolean());
    }

    @Test
    @DisplayName("a CTE is allowed because it reads")
    void cteIsAllowed() {
        ToolResult result = adapter.execute("run_sql", context(Json.obj()
                .put("sql", "WITH totals AS (SELECT count(*) AS n FROM orders) SELECT n FROM totals")));

        assertEquals(3, structured(result).path("rows").get(0).get(0).asInt());
    }

    @Test
    @DisplayName("write statements never reach the driver")
    void writesAreRejected() {
        for (String sql : List.of(
                "DELETE FROM orders",
                "INSERT INTO orders VALUES (4,'dave',1.00)",
                "UPDATE orders SET amount = 0",
                "DROP TABLE orders")) {
            ToolResult result = adapter.execute("run_sql", context(Json.obj().put("sql", sql)));
            assertTrue(result.isError(), () -> "should have been rejected: " + sql);
        }

        // The table is still there, which is the point of the guard.
        ToolResult after = adapter.execute("run_sql", context(Json.obj().put("sql", "SELECT count(*) FROM orders")));
        assertEquals(3, structured(after).path("rows").get(0).get(0).asInt());
    }

    @Test
    @DisplayName("a second statement is rejected even when both halves are reads")
    void multipleStatementsAreRejected() {
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 1; SELECT 2").isPresent());
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 1; DROP TABLE orders").isPresent());
    }

    @Test
    @DisplayName("a trailing semicolon is fine")
    void trailingSemicolonIsFine() {
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 1;").isEmpty());
    }

    @Test
    @DisplayName("a forbidden word inside a string literal is not a keyword")
    void literalsAreStrippedBeforeChecking() {
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 'drop table orders' AS warning").isEmpty());
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 1 -- delete from orders\n").isEmpty());
        assertTrue(DuckDBQueryAdapter.rejectUnsafe("SELECT 1 /* update orders */").isEmpty());
    }

    @Test
    @DisplayName("broken SQL is a failed tool call, not an exception")
    void brokenSqlBecomesFailedResult() {
        ToolResult result = adapter.execute("run_sql",
                context(Json.obj().put("sql", "SELECT * FROM missing_table")));

        assertTrue(result.isError());
        assertTrue(result.content().get(0).path("text").asString().contains("SQL error"));
    }

    @Test
    @DisplayName("health probes the database")
    void healthIsUp() {
        assertTrue(adapter.health().isUp());
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode structured(ToolResult result) {
        assertFalse(result.isError(), () -> "unexpected failure: " + result.content());
        return result.structuredContent();
    }

    private static RequestContext context(JsonNode arguments) {
        return new RequestContext("analytics_run_sql", arguments, Map.of(), null,
                Json.obj(), null, null, StreamEmitter.noop());
    }
}