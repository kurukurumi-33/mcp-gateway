package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.demo.SystemAdapter;
import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the same scenarios the Python demo covers, so the two implementations can be compared
 * side by side and the Java engine is held to the same protocol behaviours.
 */
class McpServerEngineTest {

    private final AtomicInteger ids = new AtomicInteger();

    private McpServerEngine engine(List<BackendAdapter> adapters) {
        return new McpServerEngine(
                new ToolRegistry(adapters),
                RequestStateCodec.random(),
                new McpServerEngine.ServerInfo("mcp-gateway-test", "0.1.0"));
    }

    private McpServerEngine engine() {
        return engine(List.of(new SystemAdapter(), new StorageAdapter(), new ComputeAdapter()));
    }

    // ---------------------------------------------------------------- helpers

    private McpRequest request(String method, ObjectNode params, String nameHeader) {
        ObjectNode body = Json.obj().put("jsonrpc", "2.0").put("id", ids.incrementAndGet());
        body.put("method", method);
        if (params != null) {
            body.set("params", params);
        }
        return new McpRequest(McpProtocol.PROTOCOL_VERSION, method, nameHeader, body);
    }

    private ObjectNode params() {
        return Json.obj().set("_meta", Json.obj()
                .put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.PROTOCOL_VERSION)
                .set(McpProtocol.META_CLIENT_CAPABILITIES, Json.obj())
                .set(McpProtocol.META_CLIENT_INFO, Json.obj()
                        .put("name", "test-client").put("version", "1.0.0")));
    }

    private JsonNode call(McpServerEngine engine, String tool, ObjectNode extraParams) {
        ObjectNode params = params().put("name", tool);
        if (extraParams != null) {
            extraParams.properties().forEach(e -> params.set(e.getKey(), e.getValue()));
        }
        EngineResult result = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL, params, tool));
        assertFalse(result.sse(), "expected a single JSON response");
        return result.terminal();
    }

    private JsonNode resultOf(JsonNode terminal) {
        JsonNode result = Json.orNull(terminal, "result");
        assertNotNull(result, "expected a result, got: " + terminal);
        return result;
    }

    private int errorCode(JsonNode terminal) {
        JsonNode error = Json.orNull(terminal, "error");
        assertNotNull(error, "expected an error, got: " + terminal);
        return error.path("code").asInt();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("1. server/discover — required method, carries versions, capabilities and serverInfo")
    void discover() {
        EngineResult result = engine().handle(request(McpProtocol.METHOD_DISCOVER, params(), null));

        assertEquals(200, result.httpStatus());
        JsonNode body = resultOf(result.terminal());

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, body.path("resultType").asString());
        assertEquals(McpProtocol.PROTOCOL_VERSION, body.path("supportedVersions").get(0).asString());
        assertTrue(body.path("capabilities").path("tools").isObject());
        assertEquals(3, body.path("capabilities")
                .path("io.github.kurukurumi33.mcp/backends").size());
        assertEquals("mcp-gateway-test",
                body.path("_meta").path(McpProtocol.META_SERVER_INFO).path("name").asString());
    }

    @Test
    @DisplayName("2. tools/list — namespaced, every tool carries availability metadata")
    void toolsList() {
        JsonNode body = resultOf(engine().handle(request(McpProtocol.METHOD_TOOLS_LIST, params(), null))
                .terminal());

        List<String> names = body.path("tools").valueStream()
                .map(t -> t.path("name").asString())
                .toList();

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, body.path("resultType").asString());
        assertTrue(names.containsAll(List.of(
                "system_ping", "storage_list_objects", "storage_delete_object", "compute_slow_sum")));
        assertEquals(4, names.size());
        assertTrue(body.has("ttlMs"));
        assertEquals("private", body.path("cacheScope").asString());
        assertTrue(body.path("tools").get(0).path("_meta")
                .path("io.github.kurukurumi33.mcp/available").asBoolean());
    }

    @Test
    @DisplayName("3. tools/call — normal call returns content plus structuredContent")
    void normalCall() {
        JsonNode body = resultOf(call(engine(), "storage_list_objects", null));

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, body.path("resultType").asString());
        assertEquals(2, body.path("structuredContent").path("count").asInt());
        assertFalse(body.has("isError"));
    }

    @Test
    @DisplayName("4. MRTR — first round asks for input, replay with the signed state completes")
    void multiRoundTrip() {
        McpServerEngine engine = engine();

        ObjectNode args = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "reports/2026-q3.parquet"));
        EngineResult first = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL, args, "storage_delete_object"));
        JsonNode round1 = resultOf(first.terminal());

        assertEquals(McpProtocol.RESULT_TYPE_INPUT_REQUIRED, round1.path("resultType").asString());
        assertTrue(round1.path("inputRequests").has("confirm_delete"));
        String state = round1.path("requestState").asString();
        assertFalse(state.isBlank());

        ObjectNode replay = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "reports/2026-q3.parquet"));
        replay.set("inputResponses", Json.obj().set("confirm_delete",
                Json.obj().put("action", "accept").set("content", Json.obj().put("confirm", true))));
        replay.put("requestState", state);

        JsonNode round2 = resultOf(engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, replay, "storage_delete_object")).terminal());

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, round2.path("resultType").asString());
        assertEquals("reports/2026-q3.parquet", round2.path("structuredContent").path("deleted").asString());
    }

    @Test
    @DisplayName("4b. MRTR — a declined confirmation fails the call instead of deleting")
    void multiRoundTripDeclined() {
        McpServerEngine engine = engine();
        ObjectNode args = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "keep-me.parquet"));

        JsonNode round1 = resultOf(engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, args, "storage_delete_object")).terminal());

        ObjectNode replay = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "keep-me.parquet"));
        replay.set("inputResponses", Json.obj().set("confirm_delete",
                Json.obj().put("action", "decline")));
        replay.put("requestState", round1.path("requestState").asString());

        JsonNode round2 = resultOf(engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, replay, "storage_delete_object")).terminal());

        assertTrue(round2.path("isError").asBoolean());
    }

    @Test
    @DisplayName("4c. MRTR — a tampered requestState is rejected, not trusted")
    void tamperedRequestState() {
        McpServerEngine engine = engine();
        ObjectNode args = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "victim.parquet"));

        JsonNode round1 = resultOf(engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, args, "storage_delete_object")).terminal());

        // Swap the payload for one that names a different object, keeping the old signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"key\":\"somebody-elses.parquet\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signature = round1.path("requestState").asString()
                .substring(round1.path("requestState").asString().indexOf('.') + 1);

        ObjectNode replay = params()
                .put("name", "storage_delete_object")
                .set("arguments", Json.obj().put("key", "victim.parquet"));
        replay.put("requestState", forgedPayload + "." + signature);

        EngineResult result = engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, replay, "storage_delete_object"));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.INVALID_PARAMS, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("5. streaming — progress and log notifications precede the response on one stream")
    void streamingNotifications() {
        McpServerEngine engine = engine();

        ObjectNode params = params()
                .put("name", "compute_slow_sum")
                .set("arguments", Json.obj().put("n", 3));
        params.path("_meta");
        ((ObjectNode) params.get("_meta")).put("progressToken", "tok-1");
        ((ObjectNode) params.get("_meta")).put(McpProtocol.META_LOG_LEVEL, "info");

        EngineResult result = engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, params, "compute_slow_sum"));

        assertTrue(result.sse(), "expected a stream");
        // 3 steps x (progress + log) notifications, then the response.
        assertEquals(7, result.messages().size());
        assertEquals("notifications/progress", result.messages().get(0).path("method").asString());
        assertEquals("notifications/message", result.messages().get(1).path("method").asString());
        assertEquals(6, resultOf(result.terminal()).path("structuredContent").path("sum").asInt());
    }

    @Test
    @DisplayName("5b. streaming — no log notifications unless the client asked for logging")
    void loggingIsOptIn() {
        McpServerEngine engine = engine();

        ObjectNode params = params()
                .put("name", "compute_slow_sum")
                .set("arguments", Json.obj().put("n", 2));
        ((ObjectNode) params.get("_meta")).put("progressToken", "tok-1");

        EngineResult result = engine.handle(
                request(McpProtocol.METHOD_TOOLS_CALL, params, "compute_slow_sum"));

        assertEquals(3, result.messages().size());
        assertTrue(result.messages().stream()
                .noneMatch(m -> "notifications/message".equals(m.path("method").asString())));
    }

    @Test
    @DisplayName("6. Mcp-Name disagreeing with the body is rejected with -32020")
    void nameHeaderMismatch() {
        ObjectNode params = params().put("name", "system_ping");

        EngineResult result = engine().handle(
                request(McpProtocol.METHOD_TOOLS_CALL, params, "system_ping_TYPO"));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("7. an unsupported protocol version is rejected with -32022")
    void unsupportedVersion() {
        McpRequest request = new McpRequest("2019-01-01", McpProtocol.METHOD_TOOLS_LIST, null,
                Json.obj().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", McpProtocol.METHOD_TOOLS_LIST).set("params", params()));

        EngineResult result = engine().handle(request);

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8. a missing MCP-Protocol-Version header is a header mismatch, not a default")
    void missingVersionHeader() {
        McpRequest request = new McpRequest(null, McpProtocol.METHOD_TOOLS_LIST, null,
                Json.obj().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", McpProtocol.METHOD_TOOLS_LIST).set("params", params()));

        EngineResult result = engine().handle(request);

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8b. a request without _meta is invalid, not version-less")
    void missingMeta() {
        McpRequest request = new McpRequest(McpProtocol.PROTOCOL_VERSION,
                McpProtocol.METHOD_TOOLS_LIST, null,
                Json.obj().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", McpProtocol.METHOD_TOOLS_LIST).set("params", Json.obj()));

        EngineResult result = engine().handle(request);

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.INVALID_PARAMS, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8c. _meta without clientCapabilities is invalid")
    void missingClientCapabilities() {
        ObjectNode params = Json.obj().set("_meta", Json.obj()
                .put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.PROTOCOL_VERSION));

        EngineResult result = engine().handle(
                request(McpProtocol.METHOD_TOOLS_LIST, params, null));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.INVALID_PARAMS, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8d. _meta version disagreeing with the header is a header mismatch")
    void metaVersionDisagreesWithHeader() {
        ObjectNode params = Json.obj().set("_meta", Json.obj()
                .put(McpProtocol.META_PROTOCOL_VERSION, "2025-11-25")
                .set(McpProtocol.META_CLIENT_CAPABILITIES, Json.obj()));

        EngineResult result = engine().handle(
                request(McpProtocol.METHOD_TOOLS_LIST, params, null));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("9. a method removed in 2026-07-28 is not found")
    void removedMethod() {
        EngineResult result = engine().handle(request("resources/subscribe", params(), null));

        assertEquals(404, result.httpStatus());
        assertEquals(McpProtocol.METHOD_NOT_FOUND, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("10. a down backend keeps its tools listed but fails calls fast")
    void downBackend() {
        BackendAdapter broken = new BackendAdapter() {
            @Override
            public String namespace() {
                return "warehouse";
            }

            @Override
            public List<ToolDefinition> tools() {
                return List.of(ToolDefinition.of("query", "Runs a query", Json.obj().put("type", "object")));
            }

            @Override
            public ToolResult execute(String toolName, RequestContext context) {
                throw new AssertionError("must not be invoked while the backend is down");
            }

            @Override
            public HealthStatus health() {
                return HealthStatus.down("connection refused");
            }
        };

        McpServerEngine engine = engine(List.of(broken));

        JsonNode listed = resultOf(engine.handle(request(McpProtocol.METHOD_TOOLS_LIST, params(), null)).terminal());
        assertEquals(1, listed.path("tools").size());
        assertFalse(listed.path("tools").get(0).path("_meta")
                .path("io.github.kurukurumi33.mcp/available").asBoolean());

        JsonNode body = resultOf(call(engine, "warehouse_query", null));
        assertTrue(body.path("isError").asBoolean());
        assertTrue(body.path("content").get(0).path("text").asString().contains("connection refused"));
    }
}