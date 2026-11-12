package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.conformance.ConformanceAdapter;
import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.demo.SystemAdapter;
import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.registry.GatewayRegistry;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the same scenarios the Python demo covers, so the two implementations can be compared
 * side by side and the Java engine is held to the same protocol behaviours.
 */
class McpServerEngineTest {

    private final AtomicInteger ids = new AtomicInteger();

    private McpServerEngine engine(List<BackendAdapter> adapters) {
        return new McpServerEngine(
                new GatewayRegistry(adapters),
                RequestStateCodec.random(),
                new McpServerEngine.ServerInfo("mcp-gateway-test", "0.1.0"));
    }

    private McpServerEngine engine() {
        return engine(List.of(new SystemAdapter(), new StorageAdapter(), new ComputeAdapter()));
    }

    private McpServerEngine engine(List<BackendAdapter> adapters, AdmissionControl admissionControl) {
        return new McpServerEngine(
                new GatewayRegistry(adapters),
                RequestStateCodec.random(),
                new McpServerEngine.ServerInfo("mcp-gateway-test", "0.1.0"),
                60_000L,
                admissionControl);
    }

    // ---------------------------------------------------------------- helpers

    private McpRequest request(String method, ObjectNode params, String nameHeader) {
        ObjectNode body = Json.obj().put("jsonrpc", "2.0").put("id", ids.incrementAndGet());
        body.put("method", method);
        if (params != null) {
            body.set("params", params);
        }
        return McpRequest.of(McpProtocol.PROTOCOL_VERSION, method, nameHeader, body);
    }

    private McpRequest request(String method, ObjectNode params, String nameHeader,
                               Map<String, String> extraHeaders) {
        McpRequest base = request(method, params, nameHeader);
        Map<String, String> headers = new LinkedHashMap<>(base.headers());
        headers.putAll(extraHeaders);
        return new McpRequest(headers, base.body());
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
        McpRequest request = McpRequest.of("2019-01-01", McpProtocol.METHOD_TOOLS_LIST, null,
                Json.obj().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", McpProtocol.METHOD_TOOLS_LIST).set("params", params()));

        EngineResult result = engine().handle(request);

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8. a missing MCP-Protocol-Version header is a header mismatch, not a default")
    void missingVersionHeader() {
        McpRequest request = McpRequest.of(null, McpProtocol.METHOD_TOOLS_LIST, null,
                Json.obj().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", McpProtocol.METHOD_TOOLS_LIST).set("params", params()));

        EngineResult result = engine().handle(request);

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("8b. a request without _meta is invalid, not version-less")
    void missingMeta() {
        McpRequest request = McpRequest.of(McpProtocol.PROTOCOL_VERSION,
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

    // ---------------------------------------------------------------- resources and prompts

    @Test
    @DisplayName("11. discover advertises the capabilities it now implements")
    void discoverAdvertisesResourcesAndPrompts() {
        JsonNode capabilities = resultOf(engine().handle(
                request(McpProtocol.METHOD_DISCOVER, params(), null)).terminal()).path("capabilities");

        assertTrue(capabilities.path("resources").isObject());
        assertTrue(capabilities.path("prompts").isObject());
        assertFalse(capabilities.path("resources").path("listChanged").asBoolean());
    }

    @Test
    @DisplayName("12. resources/list publishes qualified uris and the owning backend")
    void resourcesList() {
        JsonNode body = resultOf(engine().handle(
                request(McpProtocol.METHOD_RESOURCES_LIST, params(), null)).terminal());

        List<String> uris = body.path("resources").valueStream()
                .map(r -> r.path("uri").asString())
                .toList();

        assertEquals(3, uris.size());
        assertTrue(uris.containsAll(List.of(
                "mcp://system/gateway/about.md",
                "mcp://storage/reports/2026-q3.parquet",
                "mcp://storage/raw/events-0001.jsonl")));
        assertTrue(body.has("ttlMs"));
        assertEquals("private", body.path("cacheScope").asString());
        assertEquals("storage", body.path("resources").get(1).path("_meta")
                .path("io.github.kurukurumi33.mcp/backend").asString());
    }

    @Test
    @DisplayName("13. resources/templates/list publishes the template inside its namespace")
    void resourceTemplatesList() {
        JsonNode body = resultOf(engine().handle(
                request(McpProtocol.METHOD_RESOURCES_TEMPLATES_LIST, params(), null)).terminal());

        assertEquals(1, body.path("resourceTemplates").size());
        assertEquals("mcp://storage/{+key}",
                body.path("resourceTemplates").get(0).path("uriTemplate").asString());
    }

    @Test
    @DisplayName("14. resources/read returns inline text for a text object")
    void readTextResource() {
        String uri = "mcp://storage/raw/events-0001.jsonl";
        ObjectNode params = params().put("uri", uri);

        JsonNode body = resultOf(engine().handle(
                request(McpProtocol.METHOD_RESOURCES_READ, params, uri)).terminal());

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, body.path("resultType").asString());
        JsonNode entry = body.path("contents").get(0);
        assertEquals(uri, entry.path("uri").asString());
        assertEquals("application/x-ndjson", entry.path("mimeType").asString());
        assertTrue(entry.path("text").asString().contains("\"event\":\"login\""));
        assertFalse(entry.has("blob"));
    }

    @Test
    @DisplayName("15. resources/read returns base64 for a binary object")
    void readBinaryResource() {
        String uri = "mcp://storage/reports/2026-q3.parquet";
        ObjectNode params = params().put("uri", uri);

        JsonNode entry = resultOf(engine().handle(
                request(McpProtocol.METHOD_RESOURCES_READ, params, uri)).terminal())
                .path("contents").get(0);

        assertFalse(entry.has("text"));
        String decoded = new String(java.util.Base64.getDecoder().decode(entry.path("blob").asString()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(decoded.startsWith("PAR1"));
    }

    @Test
    @DisplayName("16. resources/read on an unknown path is -32602 with the uri in data")
    void readUnknownResource() {
        String uri = "mcp://storage/nope.txt";
        EngineResult result = engine().handle(
                request(McpProtocol.METHOD_RESOURCES_READ, params().put("uri", uri), uri));

        assertEquals(400, result.httpStatus());
        JsonNode error = result.terminal().path("error");
        assertEquals(McpProtocol.INVALID_PARAMS, error.path("code").asInt());
        assertEquals(uri, error.path("data").path("uri").asString());
        assertFalse(result.terminal().has("result"), "must not answer with an empty contents array");
    }

    @Test
    @DisplayName("16b. a uri with a foreign scheme is not routed anywhere")
    void readForeignSchemeResource() {
        // The same probe the official conformance suite sends; see sep-2164.
        String uri = "test://nonexistent-resource-for-conformance-testing";
        EngineResult result = engine().handle(
                request(McpProtocol.METHOD_RESOURCES_READ, params().put("uri", uri), uri));

        assertEquals(400, result.httpStatus());
        assertEquals(uri, result.terminal().path("error").path("data").path("uri").asString());
    }

    @Test
    @DisplayName("17. prompts/list names are namespaced and required arguments are marked")
    void promptsList() {
        JsonNode body = resultOf(engine().handle(
                request(McpProtocol.METHOD_PROMPTS_LIST, params(), null)).terminal());

        JsonNode prompt = body.path("prompts").get(0);
        assertEquals("system_explain_backend", prompt.path("name").asString());
        assertEquals("namespace", prompt.path("arguments").get(0).path("name").asString());
        assertTrue(prompt.path("arguments").get(0).path("required").asBoolean());
    }

    @Test
    @DisplayName("18. prompts/get renders messages through the owning adapter")
    void promptsGet() {
        String name = "system_explain_backend";
        ObjectNode params = params().put("name", name)
                .set("arguments", Json.obj().put("namespace", "storage"));

        JsonNode body = resultOf(engine().handle(
                request(McpProtocol.METHOD_PROMPTS_GET, params, name)).terminal());

        assertEquals("user", body.path("messages").get(0).path("role").asString());
        assertEquals("text", body.path("messages").get(0).path("content").path("type").asString());
        assertTrue(body.path("messages").get(0).path("content").path("text").asString()
                .contains("'storage'"));
    }

    @Test
    @DisplayName("18b. prompts/get without a required argument is -32602")
    void promptsGetMissingArgument() {
        String name = "system_explain_backend";
        EngineResult result = engine().handle(request(McpProtocol.METHOD_PROMPTS_GET,
                params().put("name", name), name));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.INVALID_PARAMS, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("18c. prompts/get on an unknown prompt is -32602 with the name in data")
    void promptsGetUnknown() {
        String name = "system_nope";
        EngineResult result = engine().handle(request(McpProtocol.METHOD_PROMPTS_GET,
                params().put("name", name), name));

        assertEquals(400, result.httpStatus());
        assertEquals(name, result.terminal().path("error").path("data").path("name").asString());
    }

    // ---------------------------------------------------------------- SEP-2243 / SEP-2322

    private McpServerEngine conformanceEngine() {
        return engine(List.of(new ConformanceAdapter()));
    }

    private EngineResult callCustomHeader(String bodyRegion, Map<String, String> headers) {
        ObjectNode params = params().put("name", "test_custom_headers")
                .set("arguments", Json.obj().put("region", bodyRegion));
        return conformanceEngine().handle(request(McpProtocol.METHOD_TOOLS_CALL,
                params, "test_custom_headers", headers));
    }

    @Test
    @DisplayName("19. a mirrored header disagreeing with the body is rejected with -32020")
    void mirroredHeaderMismatch() {
        EngineResult result = callCustomHeader("eu-west-1", Map.of("Mcp-Param-region", "us-east-1"));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("19b. omitting a mirrored header while the body carries the value is rejected")
    void mirroredHeaderMissing() {
        EngineResult result = callCustomHeader("eu-west-1", Map.of());

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("19c. a Base64-wrapped header is decoded before it is compared")
    void mirroredHeaderBase64() {
        // "Hello" in padded Base64, which is the only form the extension accepts.
        EngineResult result = callCustomHeader("Hello", Map.of("Mcp-Param-region", "=?base64?SGVsbG8=?="));

        assertEquals(200, result.httpStatus());
        assertEquals("header matched: Hello",
                resultOf(result.terminal()).path("content").get(0).path("text").asString());
    }

    @Test
    @DisplayName("19d. Base64 without the required padding is rejected, not silently decoded")
    void mirroredHeaderUnpaddedBase64() {
        EngineResult result = callCustomHeader("Hello", Map.of("Mcp-Param-region", "=?base64?SGVsbG8?="));

        assertEquals(400, result.httpStatus());
        assertEquals(McpProtocol.HEADER_MISMATCH, errorCode(result.terminal()));
    }

    @Test
    @DisplayName("20. prompts/get can ask for input and complete on the next round")
    void promptInputRequired() {
        String name = "test_input_required_result_prompt";
        McpServerEngine engine = conformanceEngine();

        JsonNode first = resultOf(engine.handle(
                request(McpProtocol.METHOD_PROMPTS_GET, params().put("name", name), name)).terminal());

        assertEquals(McpProtocol.RESULT_TYPE_INPUT_REQUIRED, first.path("resultType").asString());
        assertEquals("elicitation/create",
                first.path("inputRequests").path("user_context").path("method").asString());
        String state = first.path("requestState").asString();
        assertFalse(state.isBlank(), "the engine must sign the continuation state");

        ObjectNode second = params().put("name", name).put("requestState", state)
                .set("inputResponses", Json.obj().set("user_context", Json.obj()
                        .put("action", "accept")
                        .set("content", Json.obj().put("context", "incident notes"))));

        JsonNode complete = resultOf(engine.handle(
                request(McpProtocol.METHOD_PROMPTS_GET, second, name)).terminal());

        assertEquals(McpProtocol.RESULT_TYPE_COMPLETE, complete.path("resultType").asString());
        assertEquals("user", complete.path("messages").get(0).path("role").asString());
        assertTrue(complete.path("messages").get(0).path("content").path("text").asString()
                .contains("incident notes"));
    }

    // ---------------------------------------------------------------- admission control

    @Test
    @DisplayName("21. a call that cannot get a backend slot is shed with 429 and Retry-After")
    void bulkheadRejectsWhenFull() throws Exception {
        GateAdapter adapter = new GateAdapter();
        McpServerEngine engine = engine(List.of(adapter), AdmissionControl.of(1, 0, 0, 0));

        AtomicReference<EngineResult> first = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> first.set(engine.handle(request(
                McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"))));

        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "the first call should reach the backend");
        try {
            EngineResult second = engine.handle(request(
                    McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"));

            assertEquals(429, second.httpStatus());
            assertEquals(McpProtocol.SERVER_BUSY, errorCode(second.terminal()));
            assertEquals("1", second.headers().get("Retry-After"));
            assertEquals(1, adapter.executions.get(), "the shed call must not reach the backend");
        } finally {
            adapter.releaseGate.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertEquals(200, first.get().httpStatus());
    }

    @Test
    @DisplayName("22. a call that outlives the deadline is 504 and then gives its slot back")
    void callDeadline() throws InterruptedException {
        SleepOnceAdapter adapter = new SleepOnceAdapter();
        McpServerEngine engine = engine(List.of(adapter), AdmissionControl.of(1, 0, 0, 100));

        long started = System.nanoTime();
        EngineResult timedOut = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL,
                params().put("name", "slow_sleep_once"), "slow_sleep_once"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(504, timedOut.httpStatus());
        assertEquals(McpProtocol.BACKEND_TIMEOUT, errorCode(timedOut.terminal()));
        assertEquals(100, timedOut.terminal().path("error").path("data").path("timeoutMs").asInt());
        assertTrue(elapsedMs < 5_000, "the gateway must stop waiting at the deadline; took " + elapsedMs + " ms");

        // The interrupted worker releases its permit in a finally block; poll rather than assume
        // the release landed before this thread woke up.
        EngineResult retry = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            retry = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL,
                    params().put("name", "slow_sleep_once"), "slow_sleep_once"));
            if (retry.httpStatus() != 429) {
                break;
            }
            Thread.sleep(10);
        }

        assertNotNull(retry);
        assertEquals(200, retry.httpStatus());
        assertEquals("fast",
                resultOf(retry.terminal()).path("content").get(0).path("text").asString());
    }

    @Test
    @DisplayName("23. a configured queue let a caller wait for the slot instead of shedding it")
    void queueWaitGrantsPermit() throws Exception {
        GateAdapter adapter = new GateAdapter();
        McpServerEngine engine = engine(List.of(adapter), AdmissionControl.of(1, 1, 5_000, 0));

        AtomicReference<EngineResult> first = new AtomicReference<>();
        Thread holder = Thread.ofVirtual().start(() -> first.set(engine.handle(request(
                McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"))));
        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "the first call should reach the backend");

        AtomicReference<EngineResult> waiter = new AtomicReference<>();
        Thread queued = Thread.ofVirtual().start(() -> waiter.set(engine.handle(request(
                McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"))));

        Thread.sleep(100);
        assertNull(waiter.get(), "the second call must still be waiting for the only slot");

        adapter.releaseGate.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(5));
        queued.join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(200, first.get().httpStatus());
        assertNotNull(waiter.get());
        assertEquals(200, waiter.get().httpStatus());
        assertEquals(2, adapter.executions.get());
    }

    @Test
    @DisplayName("24. with no queue capacity a waiter is shed even when a wait is configured")
    void queueCapacityZeroSheds() throws Exception {
        GateAdapter adapter = new GateAdapter();
        McpServerEngine engine = engine(List.of(adapter), AdmissionControl.of(1, 0, 5_000, 0));

        AtomicReference<EngineResult> first = new AtomicReference<>();
        Thread holder = Thread.ofVirtual().start(() -> first.set(engine.handle(request(
                McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"))));
        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "the first call should reach the backend");
        try {
            EngineResult second = engine.handle(request(
                    McpProtocol.METHOD_TOOLS_CALL, params().put("name", "gate_hold"), "gate_hold"));

            assertEquals(429, second.httpStatus());
            assertEquals(McpProtocol.SERVER_BUSY, errorCode(second.terminal()));
        } finally {
            adapter.releaseGate.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertEquals(200, first.get().httpStatus());
    }

    @Test
    @DisplayName("25. the shared limiter can shed a call before it reaches the backend")
    void sharedLimiterDenies() {
        GateAdapter adapter = new GateAdapter();
        CallLimiter limiter = backend -> CallLimiter.Decision.deny(2_500);
        McpServerEngine engine = engine(List.of(adapter), AdmissionControl.of(0, 0, 0, 0, limiter));

        EngineResult result = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL,
                params().put("name", "gate_hold"), "gate_hold"));

        assertEquals(429, result.httpStatus());
        assertEquals(McpProtocol.SERVER_BUSY, errorCode(result.terminal()));
        assertEquals("3", result.headers().get("Retry-After"), "2500 ms rounds up to 3 seconds");
        assertEquals("rate_limit",
                result.terminal().path("error").path("data").path("reason").asString());
        assertEquals(0, adapter.executions.get(), "a rate-limited call must not reach the backend");
    }

    @Test
    @DisplayName("26. a limiter that allows leaves the call untouched")
    void sharedLimiterAllows() {
        GateAdapter adapter = new GateAdapter();
        adapter.releaseGate.countDown();
        McpServerEngine engine = engine(List.of(adapter),
                AdmissionControl.of(0, 0, 0, 0, backend -> CallLimiter.Decision.allow()));

        EngineResult result = engine.handle(request(McpProtocol.METHOD_TOOLS_CALL,
                params().put("name", "gate_hold"), "gate_hold"));

        assertEquals(200, result.httpStatus());
        assertEquals(1, adapter.executions.get());
    }

    /** Blocks every call until the test opens the gate, so the bulkhead can be observed full. */
    private static final class GateAdapter implements BackendAdapter {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch releaseGate = new CountDownLatch(1);
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String namespace() {
            return "gate";
        }

        @Override
        public String description() {
            return "holds calls until the test releases them";
        }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(ToolDefinition.builder("hold")
                    .description("Blocks until the test opens the gate")
                    .build());
        }

        @Override
        public ToolResult execute(String toolName, RequestContext context) {
            executions.incrementAndGet();
            entered.countDown();
            try {
                if (!releaseGate.await(10, TimeUnit.SECONDS)) {
                    return ToolResult.error("the test never released the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return ToolResult.text("released");
        }
    }

    /** Sleeps on the first call and answers instantly afterwards. */
    private static final class SleepOnceAdapter implements BackendAdapter {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String namespace() {
            return "slow";
        }

        @Override
        public String description() {
            return "sleeps once, then is fast";
        }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(ToolDefinition.builder("sleep_once")
                    .description("Takes longer than the deadline on its first call")
                    .build());
        }

        @Override
        public ToolResult execute(String toolName, RequestContext context) {
            if (calls.incrementAndGet() == 1) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return ToolResult.text("fast");
        }
    }
}