package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.registry.GatewayRegistry;
import io.github.kurukurumi33.mcp.registry.RegisteredPrompt;
import io.github.kurukurumi33.mcp.registry.RegisteredResource;
import io.github.kurukurumi33.mcp.registry.RegisteredResourceTemplate;
import io.github.kurukurumi33.mcp.registry.RegisteredTool;
import io.github.kurukurumi33.mcp.registry.ResourceUris;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.InputRequest;
import io.github.kurukurumi33.mcp.spi.InputResponse;
import io.github.kurukurumi33.mcp.spi.PromptArgument;
import io.github.kurukurumi33.mcp.spi.PromptContext;
import io.github.kurukurumi33.mcp.spi.PromptDefinition;
import io.github.kurukurumi33.mcp.spi.PromptResult;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ResourceContent;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;
import io.github.kurukurumi33.mcp.spi.StreamEmitter;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final String BASE64_WRAPPER_PREFIX = "=?base64?";
    private static final String BASE64_WRAPPER_SUFFIX = "?=";
    private static final Pattern BASE64_ALPHABET = Pattern.compile("[A-Za-z0-9+/]*={0,2}");

    /** Methods whose {@code Mcp-Name} header is mandatory and must match the body. */
    private static final Set<String> NAMED_METHODS =
            Set.of(McpProtocol.METHOD_TOOLS_CALL, McpProtocol.METHOD_PROMPTS_GET,
                    McpProtocol.METHOD_RESOURCES_READ);

    private final GatewayRegistry registry;
    private final RequestStateCodec stateCodec;
    private final ServerInfo serverInfo;
    private final long cacheTtlMs;
    private final AdmissionControl admissionControl;

    public McpServerEngine(GatewayRegistry registry, RequestStateCodec stateCodec, ServerInfo serverInfo) {
        this(registry, stateCodec, serverInfo, 60_000L);
    }

    public McpServerEngine(GatewayRegistry registry, RequestStateCodec stateCodec,
                           ServerInfo serverInfo, long cacheTtlMs) {
        this(registry, stateCodec, serverInfo, cacheTtlMs, AdmissionControl.unlimited());
    }

    /**
     * @param admissionControl per-backend bulkheads and the call deadline; pass
     *                         {@link AdmissionControl#unlimited()} to run adapter calls inline
     */
    public McpServerEngine(GatewayRegistry registry, RequestStateCodec stateCodec,
                           ServerInfo serverInfo, long cacheTtlMs,
                           AdmissionControl admissionControl) {
        this.registry = registry;
        this.stateCodec = stateCodec;
        this.serverInfo = serverInfo;
        this.cacheTtlMs = cacheTtlMs;
        this.admissionControl = admissionControl;
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
                    errorMessage(request, e.code(), e.getMessage(), e.data()),
                    e.httpStatus(), e.headers());
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
            case McpProtocol.METHOD_RESOURCES_LIST -> EngineResult.json(resourcesList(request));
            case McpProtocol.METHOD_RESOURCES_TEMPLATES_LIST -> EngineResult.json(resourceTemplatesList(request));
            case McpProtocol.METHOD_RESOURCES_READ -> EngineResult.json(resourcesRead(request));
            case McpProtocol.METHOD_PROMPTS_LIST -> EngineResult.json(promptsList(request));
            case McpProtocol.METHOD_PROMPTS_GET -> EngineResult.json(promptsGet(request));
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

    /**
     * Checks the optional parameter mirrors a tool declares through {@code "x-mcp-header"}.
     *
     * <p>An argument marked that way is repeated in an HTTP header so an intermediary can act on it
     * without parsing the body. The body is still what the tool runs on, so a disagreement means the
     * request would be routed and executed as two different things — the same defect {@link
     * #validateNameHeader} guards, and the same answer.
     *
     * <p>Values wrapped as {@code =?base64?...?=} are Base64; anything else is literal. The encoding
     * exists because header values cannot carry arbitrary bytes, not as a security boundary.
     */
    private void validateParameterHeaders(McpRequest request, RegisteredTool tool, JsonNode arguments) {
        JsonNode properties = Json.orNull(tool.definition().inputSchema(), "properties");
        if (properties == null || !properties.isObject() || arguments == null || !arguments.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            JsonNode marker = Json.orNull(property.getValue(), "x-mcp-header");
            if (marker == null || !marker.isTextual() || marker.asString().isBlank()) {
                continue;
            }
            JsonNode bodyValue = Json.orNull(arguments, property.getKey());
            if (bodyValue == null || !bodyValue.isTextual()) {
                // Nothing in the body to mirror, so nothing the header could contradict.
                continue;
            }
            String headerName = McpProtocol.HEADER_PARAM_PREFIX + marker.asString();
            String headerValue = request.header(headerName);
            if (headerValue == null) {
                throw McpException.headerMismatch(
                        "Header '" + headerName + "' is required when the body carries '" + property.getKey() + "'");
            }
            String decoded = decodeParameterHeader(headerName, headerValue);
            if (!decoded.equals(bodyValue.asString())) {
                throw McpException.headerMismatch(
                        "Header '" + headerName + "' does not match argument '" + property.getKey() + "'");
            }
        }
    }

    private static String decodeParameterHeader(String headerName, String value) {
        boolean wrapped = value.startsWith(BASE64_WRAPPER_PREFIX) && value.endsWith(BASE64_WRAPPER_SUFFIX);
        if (!wrapped) {
            return value;
        }
        String encoded = value.substring(BASE64_WRAPPER_PREFIX.length(),
                value.length() - BASE64_WRAPPER_SUFFIX.length());
        // Checked explicitly: java.util.Base64 accepts unpadded input, while the extension does not.
        if (encoded.length() % 4 != 0 || !BASE64_ALPHABET.matcher(encoded).matches()) {
            throw McpException.headerMismatch("Header '" + headerName + "' is not valid Base64");
        }
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw McpException.headerMismatch("Header '" + headerName + "' is not valid Base64");
        }
    }

    // ------------------------------------------------------------------ methods

    private JsonNode discover(McpRequest request) {
        ObjectNode capabilities = Json.obj();
        // listChanged stays false everywhere: the catalog is built once at startup, so a client
        // never has to subscribe to a change feed that will not fire.
        capabilities.set("tools", Json.obj().put("listChanged", false));
        capabilities.set("resources", Json.obj().put("listChanged", false));
        capabilities.set("prompts", Json.obj().put("listChanged", false));

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

        RegisteredTool tool = registry.findTool(qualifiedName)
                .orElseThrow(() -> McpException.invalidParams("Unknown tool '" + qualifiedName + "'"));

        JsonNode arguments = Json.orNull(params, "arguments");
        validateParameterHeaders(request, tool, arguments);

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
                arguments,
                inputResponses,
                continuationState,
                Json.orNull(Json.orNull(params, "_meta"), McpProtocol.META_CLIENT_CAPABILITIES),
                firstText(Json.orNull(params, "_meta"), "progressToken"),
                firstText(Json.orNull(params, "_meta"), McpProtocol.META_LOG_LEVEL),
                emitter);

        ToolResult result = admissionControl.call(
                tool.adapter().namespace(),
                tool.definition().name(),
                () -> tool.adapter().execute(tool.definition().name(), context));
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

    // ------------------------------------------------------------------ resources

    private JsonNode resourcesList(McpRequest request) {
        ArrayNode resources = Json.arr();
        for (RegisteredResource resource : registry.resources()) {
            resources.add(describeResource(resource));
        }

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("resources", resources);
        result.put("ttlMs", cacheTtlMs);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());
        return envelope(request, result);
    }

    private ObjectNode describeResource(RegisteredResource resource) {
        ResourceDefinition def = resource.definition();
        ObjectNode node = Json.obj();
        node.put("uri", resource.qualifiedUri());
        node.put("name", def.name());
        if (def.mimeType() != null) {
            node.put("mimeType", def.mimeType());
        }
        if (def.description() != null) {
            node.put("description", def.description());
        }

        ObjectNode meta = Json.obj();
        meta.put(META_AVAILABLE, resource.available());
        meta.put(META_BACKEND, resource.adapter().namespace());
        node.set("_meta", meta);
        return node;
    }

    private JsonNode resourceTemplatesList(McpRequest request) {
        ArrayNode templates = Json.arr();
        for (RegisteredResourceTemplate registered : registry.resourceTemplates()) {
            ObjectNode node = Json.obj();
            node.put("uriTemplate", registered.qualifiedUriTemplate());
            node.put("name", registered.template().name());
            if (registered.template().mimeType() != null) {
                node.put("mimeType", registered.template().mimeType());
            }
            if (registered.template().description() != null) {
                node.put("description", registered.template().description());
            }
            templates.add(node);
        }

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("resourceTemplates", templates);
        result.put("ttlMs", cacheTtlMs);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());
        return envelope(request, result);
    }

    /**
     * Reads one resource.
     *
     * <p>A uri the gateway does not own and a path its backend does not have are the same answer
     * here, {@code -32602} with the uri in {@code error.data}, because the client asked for something
     * that does not exist and has no way to act on the difference. This is deliberately not the
     * tool-error shape: the model is not making this call, the host is, so the host must see it fail.
     */
    private JsonNode resourcesRead(McpRequest request) {
        JsonNode params = request.params();
        String uri = params == null ? null : firstText(params, "uri");
        if (uri == null || uri.isBlank()) {
            throw McpException.invalidParams("resources/read requires params.uri");
        }

        ResourceUris.Parsed target = registry.resourceTarget(uri).orElseThrow(() -> resourceNotFound(uri));
        BackendAdapter adapter = registry.adapter(target.namespace()).orElseThrow(() -> resourceNotFound(uri));

        HealthStatus health = adapter.health();
        if (!health.isCallable()) {
            throw new McpException(McpProtocol.INTERNAL_ERROR, 503,
                    "Backend '" + adapter.namespace() + "' is unavailable: " + health.detail());
        }

        ResourceContent content = admissionControl.call(
                        adapter.namespace(), target.uri(),
                        () -> adapter.readResource(target.uri()))
                .orElseThrow(() -> resourceNotFound(uri));

        ObjectNode entry = Json.obj();
        entry.put("uri", uri);
        if (content.mimeType() != null) {
            entry.put("mimeType", content.mimeType());
        }
        if (content.isBinary()) {
            entry.put("blob", Base64.getEncoder().encodeToString(content.blob()));
        } else {
            entry.put("text", content.text());
        }

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("contents", Json.arr().add(entry));
        result.put("ttlMs", cacheTtlMs);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());
        return envelope(request, result);
    }

    private static McpException resourceNotFound(String uri) {
        return new McpException(McpProtocol.INVALID_PARAMS, 400,
                "Resource not found: " + uri, Json.obj().put("uri", uri));
    }

    // ------------------------------------------------------------------ prompts

    private JsonNode promptsList(McpRequest request) {
        ArrayNode prompts = Json.arr();
        for (RegisteredPrompt prompt : registry.prompts()) {
            prompts.add(describePrompt(prompt));
        }

        ObjectNode result = Json.obj();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.set("prompts", prompts);
        result.put("ttlMs", cacheTtlMs);
        result.put("cacheScope", "private");
        result.set("_meta", serverMeta());
        return envelope(request, result);
    }

    private ObjectNode describePrompt(RegisteredPrompt prompt) {
        PromptDefinition def = prompt.definition();
        ObjectNode node = Json.obj();
        node.put("name", prompt.qualifiedName());
        if (def.description() != null) {
            node.put("description", def.description());
        }
        if (!def.arguments().isEmpty()) {
            ArrayNode arguments = Json.arr();
            for (PromptArgument argument : def.arguments()) {
                ObjectNode arg = Json.obj().put("name", argument.name());
                if (argument.description() != null) {
                    arg.put("description", argument.description());
                }
                if (argument.required()) {
                    arg.put("required", true);
                }
                arguments.add(arg);
            }
            node.set("arguments", arguments);
        }
        return node;
    }

    private JsonNode promptsGet(McpRequest request) {
        JsonNode params = request.params();
        String qualifiedName = params == null ? null : firstText(params, "name");
        if (qualifiedName == null) {
            throw McpException.invalidParams("prompts/get requires params.name");
        }

        RegisteredPrompt prompt = registry.findPrompt(qualifiedName)
                .orElseThrow(() -> promptNotFound(qualifiedName));

        HealthStatus health = prompt.adapter().health();
        if (!health.isCallable()) {
            throw new McpException(McpProtocol.INTERNAL_ERROR, 503,
                    "Backend '" + prompt.adapter().namespace() + "' is unavailable: " + health.detail());
        }

        JsonNode arguments = Json.orNull(params, "arguments");
        JsonNode supplied = arguments == null ? Json.obj() : arguments;
        validatePromptArguments(prompt.definition(), qualifiedName, supplied);

        PromptContext context = new PromptContext(
                prompt.definition().name(),
                supplied,
                parseInputResponses(params),
                decodeContinuation(params));

        PromptResult rendered = admissionControl.call(
                        prompt.adapter().namespace(),
                        prompt.definition().name(),
                        () -> prompt.adapter().getPrompt(prompt.definition().name(), context))
                .orElseThrow(() -> promptNotFound(qualifiedName));

        return envelope(request, renderPromptResult(rendered));
    }

    private static McpException promptNotFound(String name) {
        return new McpException(McpProtocol.INVALID_PARAMS, 400,
                "Prompt not found: " + name, Json.obj().put("name", name));
    }

    /**
     * Rejects a call that omits a required prompt argument.
     *
     * <p>Done here rather than in each adapter because the arguments are declared in
     * {@link PromptDefinition}, and the protocol wants this answer as {@code -32602} rather than as an
     * empty result, which the adapter's {@code Optional} could not distinguish from an unknown prompt.
     */
    private static void validatePromptArguments(PromptDefinition definition, String qualifiedName,
                                                JsonNode arguments) {
        if (!arguments.isObject()) {
            throw McpException.invalidParams("prompts/get params.arguments must be an object");
        }
        for (PromptArgument argument : definition.arguments()) {
            if (!argument.required()) {
                continue;
            }
            JsonNode value = Json.orNull(arguments, argument.name());
            if (value == null || (value.isTextual() && value.asString().isBlank())) {
                throw McpException.invalidParams("prompt '" + qualifiedName
                        + "' requires argument '" + argument.name() + "'");
            }
        }
    }

    // ------------------------------------------------------------------ result shaping

    private ObjectNode renderResult(ToolResult result) {
        if (result.inputRequired()) {
            return inputRequiredResult(result.inputRequests(), result.continuationState());
        }

        ObjectNode node = Json.obj();
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

    private ObjectNode renderPromptResult(PromptResult result) {
        if (result.inputRequired()) {
            return inputRequiredResult(result.inputRequests(), result.continuationState());
        }

        ObjectNode node = Json.obj();
        node.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        if (result.description() != null) {
            node.put("description", result.description());
        }
        ArrayNode messages = Json.arr();
        result.messages().forEach(messages::add);
        node.set("messages", messages);
        node.set("_meta", serverMeta());
        return node;
    }

    /**
     * The shape MRTR shares across methods: pending requests plus the state that links the rounds.
     *
     * <p>The handler's private state is signed here, not handed over raw, so a client cannot rewrite
     * the operation it is resuming.
     */
    private ObjectNode inputRequiredResult(Map<String, InputRequest> inputRequests, JsonNode continuationState) {
        ObjectNode node = Json.obj();
        node.put("resultType", McpProtocol.RESULT_TYPE_INPUT_REQUIRED);
        ObjectNode requests = Json.obj();
        inputRequests.forEach((id, inputRequest) -> requests.set(id, renderInputRequest(inputRequest)));
        node.set("inputRequests", requests);
        node.put("requestState", stateCodec.encode(
                continuationState == null ? Json.obj() : continuationState));
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