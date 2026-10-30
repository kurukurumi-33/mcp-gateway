package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.registry.RegisteredTool;
import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.InputRequest;
import io.github.kurukurumi33.mcp.spi.InputResponse;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.StreamEmitter;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stateless protocol engine: validates a request, routes it, and shapes the reply.
 *
 * <p>It holds no per-client state. Two requests from the same agent are as unrelated as two requests
 * from different agents, which is what lets the gateway run behind an ordinary load balancer with no
 * sticky routing. Anything that has to survive between HTTP requests travels in the signed
 * {@code requestState} token instead.
 *
 * <p>Thread-safe: the only mutable thing is the registry, which is built once and then read-only.
 */
public final class McpServerEngine {

    private static final String SERVER_INFO_KEY = McpProtocol.META_SERVER_INFO;
    private static final String BACKENDS_CAPABILITY = "io.github.kurukurumi33.mcp/backends";
    private static final String META_AVAILABLE = "io.github.kurukurumi33.mcp/available";
    private static final String META_BACKEND = "io.github.kurukurumi33.mcp/backend";

    /** Methods whose {@code Mcp-Name} header is mandatory and must match the body. */
    private static final Set<String> NAMED_METHODS =
            Set.of(McpProtocol.METHOD_TOOLS_CALL, "prompts/get", McpProtocol.METHOD_RESOURCES_READ);

    private final ToolRegistry registry;
    private final RequestStateCodec stateCodec;
    private final ServerInfo serverInfo;
    private final long cacheTtlMs;

    public McpServerEngine(ToolRegistry registry, RequestStateCodec stateCodec, ServerInfo serverInfo) {
        this(registry, stateCodec, serverInfo, 60_000L);
    }

    public McpServerEngine(ToolRegistry registry, RequestStateCodec stateCodec,
                           ServerInfo serverInfo, long cacheTtlMs) {
        this.registry = registry;
        this.stateCodec = stateCodec;
        this.serverInfo = serverInfo;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * Handles one request. Never throws: every failure, including an unexpected one, comes back as a
     * JSON-RPC error so the transport has a single shape to render.
     */
    public EngineResult handle(McpRequest request) {
        return handle(request, null);
    }

    /**
     * Handles one request, sending request-scoped notifications to {@code liveEmitter} while the tool
     * runs instead of buffering them.
     *
     * <p>A live emitter is what makes progress visible during a long job: a five-minute tool reports
     * its first step rather than every step at the end. With a null emitter the messages are collected
     * and returned in {@link EngineResult#messages()}, which is what a buffered transport and every
     * test wants.
     */
    public EngineResult handle(McpRequest request, StreamEmitter liveEmitter) {
        try {
            return dispatch(request, liveEmitter);
        } catch (McpException e) {
            return EngineResult.json(
                    errorMessage(request, e.code(), e.getMessage(), e.data()), e.httpStatus());
        } catch (RuntimeException e) {
            return EngineResult.json(
                    errorMessage(request, McpProtocol.INTERNAL_ERROR, "Internal error: " + e),
                    500);
        }
    }

    /**
     * Convenience for streaming transports: emits notifications live and returns only the terminal
     * JSON-RPC message, or {@code null} when the request was a notification (nothing to send back).
     */
    public JsonNode handleStreaming(McpRequest request, StreamEmitter liveEmitter) {
        EngineResult result = handle(request, liveEmitter);
        return result.messages().isEmpty() ? null : result.terminal();
    }

    private EngineResult dispatch(McpRequest request, StreamEmitter liveEmitter) {
        validateVersionHeader(request);

        String method = request.method();
        if (method == null || method.isBlank()) {
            throw McpException.invalidRequest("Request has no method");
        }
        validateMethodHeader(request, method);
        validateNameHeader(request, method);
        validateMeta(request);

        if (request.id() == null) {
            // A notification: the protocol acknowledges it without a body.
            return new EngineResult(202, false, List.of());
        }

        return switch (method) {
            case McpProtocol.METHOD_DISCOVER -> EngineResult.json(discover(request));
            case McpProtocol.METHOD_TOOLS_LIST -> EngineResult.json(toolsList(request));
            case McpProtocol.METHOD_TOOLS_CALL -> toolsCall(request, liveEmitter);
            default -> throw McpException.methodNotFound("This server does not implement '" + method + "'");
        };
    }

    // ------------------------------------------------------------------ header validation

    private void validateVersionHeader(McpRequest request) {
        String header = request.protocolVersionHeader();
        if (header == null || header.isBlank()) {
            // On a stateless server the version cannot be negotiated once at connect time, so an
            // absent header is not "default to latest" — it is an unverifiable request.
            throw McpException.headerMismatch(
                    McpProtocol.HEADER_PROTOCOL_VERSION + " header is required");
        }
        if (!McpProtocol.PROTOCOL_VERSION.equals(header)) {
            throw McpException.unsupportedVersion(
                    "Unsupported protocol version '" + header + "'; this server speaks "
                            + McpProtocol.PROTOCOL_VERSION, header);
        }
    }

    private void validateMethodHeader(McpRequest request, String method) {
        String header = request.methodHeader();
        if (header == null || header.isBlank()) {
            throw McpException.headerMismatch(McpProtocol.HEADER_METHOD + " header is required");
        }
        if (!header.equals(method)) {
            throw McpException.headerMismatch(
                    McpProtocol.HEADER_METHOD + " header '" + header + "' does not match body method '" + method + "'");
        }
    }

    private void validateNameHeader(McpRequest request, String method) {
        String header = request.nameHeader();
        JsonNode params = request.params();
        String bodyName = params == null ? null : firstText(params, "name", "uri");

        boolean required = NAMED_METHODS.contains(method);
        if (header == null || header.isBlank()) {
            if (required) {
                throw McpException.headerMismatch(
                        McpProtocol.HEADER_NAME + " header is required for '" + method + "'");
            }
            return;
        }
        if (bodyName != null && !header.equals(bodyName)) {
            // Intermediaries route on this header while the server executes the body. If they
            // disagree, the call is routed and executed as two different things.
            throw McpException.headerMismatch(
                    McpProtocol.HEADER_NAME + " header '" + header + "' does not match body name '" + bodyName + "'");
        }
    }

    /**
     * Validates the per-request context that replaced the {@code initialize} handshake.
     *
     * <p>Without a handshake there is nothing to negotiate against, so every request has to carry its
     * own protocol version and client capabilities; a request missing either cannot be interpreted
     * and is rejected as invalid params. {@code clientInfo} is deliberately not required — the
     * revision demoted it to SHOULD.
     *
     * <p>When the version inside {@code _meta} disagrees with the transport header, the failure is a
     * header mismatch rather than a bad parameter: the two sources of truth contradict each other,
     * which is the same defect {@link #validateMethodHeader} and {@link #validateNameHeader} guard.
     */
    private void validateMeta(McpRequest request) {
        JsonNode meta = request.meta();
        if (meta == null || !meta.isObject()) {
            throw McpException.invalidParams("params._meta is required");
        }

        JsonNode version = Json.orNull(meta, McpProtocol.META_PROTOCOL_VERSION);
        if (version == null || !version.isTextual() || version.asString().isBlank()) {
            throw McpException.invalidParams(
                    McpProtocol.META_PROTOCOL_VERSION + " is required in params._meta");
        }
        if (!version.asString().equals(request.protocolVersionHeader())) {
            throw McpException.headerMismatch(
                    "params._meta protocol version '" + version.asString()
                            + "' does not match " + McpProtocol.HEADER_PROTOCOL_VERSION + " header");
        }
        if (!McpProtocol.PROTOCOL_VERSION.equals(version.asString())) {
            throw McpException.unsupportedVersion(
                    "Unsupported protocol version '" + version.asString() + "'; this server speaks "
                            + McpProtocol.PROTOCOL_VERSION, version.asString());
        }

        JsonNode capabilities = Json.orNull(meta, McpProtocol.META_CLIENT_CAPABILITIES);
        if (capabilities == null || !capabilities.isObject()) {
            throw McpException.invalidParams(
                    McpProtocol.META_CLIENT_CAPABILITIES + " is required in params._meta");
        }
    }

    // ------------------------------------------------------------------ methods

    private JsonNode discover(McpRequest request) {
        ObjectNode capabilities = Json.obj();
        capabilities.set("tools", Json.obj().put("listChanged", false));

        ArrayNode backends = Json.arr();
        for (BackendAdapter adapter : registry.adapters()) {
            HealthStatus health = adapter.health();
            backends.add(Json.obj()
                    .put("namespace", adapter.namespace())
                    .put("description", adapter.description())
                    .put("health", health.state().name().toLowerCase())
                    .put("detail", health.detail())
                    .put("tools", adapter.tools().size()));
        }
        capabilities.set(BACKENDS_CAPABILITY, backends);

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("supportedVersions", Json.arr().add(McpProtocol.PROTOCOL_VERSION));
        result.set("capabilities", capabilities);
        result.put("ttlMs", 0L);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());

        return envelope(request, result);
    }

    private JsonNode toolsList(McpRequest request) {
        ArrayNode tools = Json.arr();
        for (RegisteredTool tool : registry.tools()) {
            tools.add(describeTool(tool));
        }

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("tools", tools);
        // The catalog is static while the process runs, but a backend can go down, so clients are
        // told to re-check rather than cache forever.
        result.put("ttlMs", cacheTtlMs);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());

        return envelope(request, result);
    }

    private ObjectNode describeTool(RegisteredTool tool) {
        ToolDefinition def = tool.definition();
        ObjectNode node = Json.obj();
        node.put("name", tool.qualifiedName());
        node.put("description", def.description());
        node.set("inputSchema", def.inputSchema());
        if (def.outputSchema() != null) {
            node.set("outputSchema", def.outputSchema());
        }

        ObjectNode meta = Json.obj();
        meta.put(META_AVAILABLE, tool.callable());
        meta.put(META_BACKEND, tool.adapter().namespace());
        node.set("_meta", meta);
        return node;
    }

    private EngineResult toolsCall(McpRequest request, StreamEmitter liveEmitter) {
        JsonNode params = request.params();
        String qualifiedName = params == null ? null : firstText(params, "name");
        if (qualifiedName == null) {
            throw McpException.invalidParams("tools/call requires params.name");
        }

        RegisteredTool tool = registry.find(qualifiedName)
                .orElseThrow(() -> McpException.invalidParams("Unknown tool '" + qualifiedName + "'"));

        HealthStatus health = tool.adapter().health();
        if (!health.isCallable()) {
            return EngineResult.json(envelope(request, failedResult(
                    "Backend '" + tool.adapter().namespace() + "' is unavailable: " + health.detail())));
        }
    
        Map<String, InputResponse> inputResponses = parseInputResponses(params);
        JsonNode continuationState = decodeContinuation(params);

        RecordingEmitter recorder = liveEmitter == null ? new RecordingEmitter() : null;
        StreamEmitter emitter = recorder != null ? recorder : liveEmitter;

        RequestContext context = new RequestContext(
                qualifiedName,
                Json.orNull(params, "arguments"),
                inputResponses,
                continuationState,
                Json.orNull(Json.orNull(params, "_meta"), McpProtocol.META_CLIENT_CAPABILITIES),
                firstText(Json.orNull(params, "_meta"), "progressToken"),
                firstText(Json.orNull(params, "_meta"), McpProtocol.META_LOG_LEVEL),
                emitter);

        ToolResult result = tool.adapter().execute(tool.definition().name(), context);
        JsonNode response = envelope(request, renderResult(result));

        //决定返回JSON流还是SSE流
        // A live emitter has already sent its notifications; only the terminal message is left.
        if (recorder == null || recorder.isEmpty()) {
            return EngineResult.json(response);
        }
        List<JsonNode> messages = new ArrayList<>(recorder.messages());
        messages.add(response);
        return EngineResult.stream(messages);
    }

    // ------------------------------------------------------------------ result shaping

    private ObjectNode renderResult(ToolResult result) {
        ObjectNode node = Json.obj();
        if (result.inputRequired()) {
            node.put("resultType", McpProtocol.RESULT_TYPE_INPUT_REQUIRED);
            ObjectNode requests = Json.obj();
            result.inputRequests().forEach((id, inputRequest) ->
                    requests.set(id, renderInputRequest(inputRequest)));
            node.set("inputRequests", requests);
            // The handler's private state is signed here, not handed over raw, so a client cannot
            // rewrite the operation it is resuming.
            node.put("requestState", stateCodec.encode(result.continuationState() == null
                    ? Json.obj() : result.continuationState()));
            return node;
        }

        node.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        ArrayNode content = Json.arr();
        result.content().forEach(content::add);
        node.set("content", content);
        if (result.structuredContent() != null) {
            node.set("structuredContent", result.structuredContent());
        }
        if (result.isError()) {
            node.put("isError", true);
        }
        node.set("_meta", serverMeta());
        return node;
    }

    private ObjectNode renderInputRequest(InputRequest inputRequest) {
        return Json.obj()
                .put("method", inputRequest.method())
                .set("params", inputRequest.params());
    }

    private ObjectNode failedResult(String message) {
        ObjectNode node = Json.obj();
        node.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        node.set("content", Json.arr().add(ToolResult.textContent(message)));
        node.put("isError", true);
        node.set("_meta", serverMeta());
        return node;
    }

    private ObjectNode envelope(McpRequest request, ObjectNode result) {
        ObjectNode root = Json.obj();
        root.put("jsonrpc", "2.0");
        if (request.id() != null) {
            root.set("id", request.id());
        }
        root.set("result", result);
        return root;
    }

    private JsonNode errorMessage(McpRequest request, int code, String message) {
        return errorMessage(request, code, message, null);
    }

    private JsonNode errorMessage(McpRequest request, int code, String message, JsonNode data) {
        ObjectNode error = Json.obj();
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isNull()) {
            error.set("data", data);
        }

        ObjectNode root = Json.obj();
        root.put("jsonrpc", "2.0");
        if (request.id() != null) {
            root.set("id", request.id());
        }
        root.set("error", error);
        return root;
    }

    private ObjectNode serverMeta() {
        return Json.obj().set(SERVER_INFO_KEY,
                Json.obj().put("name", serverInfo.name()).put("version", serverInfo.version()));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, InputResponse> parseInputResponses(JsonNode params) {
        JsonNode node = Json.orNull(params, "inputResponses");
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, InputResponse> responses = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            responses.put(entry.getKey(), new InputResponse(
                    firstText(value, "action"),
                    Json.orNull(value, "content")));
        });
        return responses;
    }

    private JsonNode decodeContinuation(JsonNode params) {
        String token = firstText(params, "requestState");
        return token == null ? null : stateCodec.decode(token);
    }

    private static String firstText(JsonNode parent, String... fields) {
        if (parent == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode node = Json.orNull(parent, field);
            if (node != null && node.isTextual()) {
                return node.asString();
            }
        }
        return null;
    }

    /** Name and version reported to clients in every result's {@code _meta}. */
    public record ServerInfo(String name, String version) {}

    /** Collects this request's notifications so the transport can replay them ahead of the response. */
    private static final class RecordingEmitter implements StreamEmitter {
        private final List<JsonNode> captured = new ArrayList<>();

        @Override
        public void notify(String method, JsonNode params) {
            captured.add(Json.obj().put("jsonrpc", "2.0").put("method", method).set("params", params));
        }

        List<JsonNode> messages() {
            return captured;
        }

        boolean isEmpty() {
            return captured.isEmpty();
        }
    }
}