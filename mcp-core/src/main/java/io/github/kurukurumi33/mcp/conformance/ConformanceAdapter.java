package io.github.kurukurumi33.mcp.conformance;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.InputRequest;
import io.github.kurukurumi33.mcp.spi.InputResponse;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic fixtures the official conformance suite drives the server with.
 *
 * <p>The suite cannot test behaviours such as "reject an undeclared capability" or "resume a
 * second round" unless the server under test exposes a tool that provokes them, so every SDK ships
 * a set of tools with fixed names. These are those tools.
 *
 * <p>Not for production: it is registered only when {@code mcp.gateway.conformance-fixtures} is
 * enabled, and its names are published unprefixed because the suite calls them verbatim.
 */
public final class ConformanceAdapter implements BackendAdapter {

    public static final String NAMESPACE = "conformance";

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    @Override
    public boolean prefixedToolNames() {
        return false;
    }

    @Override
    public String description() {
        return "Diagnostic fixtures for the official MCP conformance suite";
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                fixture("test_missing_capability",
                        "Requires the 'sampling' client capability and is rejected with -32021 without it."),
                fixture("test_logging_tool",
                        "Attempts to log; the engine must swallow it unless the client opted in."),
                fixture("test_streaming_elicitation",
                        "Reports progress on the request stream without emitting independent requests."),
                fixture("test_input_required_result_elicitation",
                        "Asks for a name through elicitation, then greets."),
                fixture("test_input_required_result_sampling",
                        "Asks the client's model a question through sampling."),
                fixture("test_input_required_result_list_roots",
                        "Asks the client for its roots."),
                fixture("test_input_required_result_request_state",
                        "Carries signed state across the round and proves it was validated."),
                fixture("test_input_required_result_tampered_state",
                        "Same as request_state; the suite edits the token and expects -32602."),
                fixture("test_input_required_result_multiple_inputs",
                        "Asks for elicitation, sampling and roots in one round."),
                fixture("test_input_required_result_multi_round",
                        "Requires three rounds, two of them asking for input."),
                fixture("test_input_required_result_capabilities",
                        "Only asks for the input methods the client declared."));
    }

    private static ToolDefinition fixture(String name, String description) {
        return ToolDefinition.builder(name).description(description).mrtr(true).build();
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        return switch (toolName) {
            case "test_missing_capability" -> missingCapability(context);
            case "test_logging_tool" -> loggingTool(context);
            case "test_streaming_elicitation" -> streamingElicitation(context);
            case "test_input_required_result_elicitation" -> elicitation(context);
            case "test_input_required_result_sampling" -> sampling(context);
            case "test_input_required_result_list_roots" -> listRoots(context);
            case "test_input_required_result_request_state" -> requestState(context);
            case "test_input_required_result_tampered_state" -> tamperedState(context);
            case "test_input_required_result_multiple_inputs" -> multipleInputs(context);
            case "test_input_required_result_multi_round" -> multiRound(context);
            case "test_input_required_result_capabilities" -> capabilities(context);
            default -> ToolResult.error("Unknown conformance fixture '" + toolName + "'");
        };
    }

    // ------------------------------------------------------------------ fixtures

    private ToolResult missingCapability(RequestContext context) {
        if (!context.hasCapability("sampling")) {
            throw McpException.missingCapability(
                    "This fixture needs the 'sampling' client capability",
                    Json.obj().set("sampling", Json.obj()));
        }
        return ToolResult.text("sampling capability present");
    }

    private ToolResult loggingTool(RequestContext context) {
        // The engine drops this unless _meta carried a logLevel; that is the behaviour under test.
        context.log("info", "conformance: this must not reach a client that did not opt in");
        return ToolResult.text("log attempted");
    }

    private ToolResult streamingElicitation(RequestContext context) {
        context.progress(1, 2.0, "first step");
        context.progress(2, 2.0, "second step");
        return ToolResult.text("streamed without asking the client anything out of band");
    }

    private ToolResult elicitation(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(
                    Map.of("user_name", nameRequest()), null);
        }
        String name = content(context, "user_name").path("name").asString("there");
        return ToolResult.text("Hello, " + name + "!");
    }

    private ToolResult sampling(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(Map.of("capital_question",
                    InputRequest.sampling(Json.obj()
                            .set("messages", Json.arr().add(Json.obj()
                                    .put("role", "user")
                                    .set("content", Json.obj()
                                            .put("type", "text")
                                            .put("text", "What is the capital of France?"))))
                            .put("maxTokens", 100))), null);
        }
        return ToolResult.text("The capital of France is Paris.");
    }

    private ToolResult listRoots(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(Map.of("client_roots", InputRequest.listRoots()), null);
        }
        JsonNode roots = content(context, "client_roots").path("roots");
        return ToolResult.text("Client reported " + roots.size() + " root(s).");
    }

    private ToolResult requestState(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(
                    Map.of("confirm", confirmationRequest()),
                    Json.obj().put("fixture", "request-state"));
        }
        JsonNode state = context.continuationState();
        if (state == null || !"request-state".equals(state.path("fixture").asString())) {
            return ToolResult.error("requestState was missing or did not round-trip");
        }
        return ToolResult.text("state-ok");
    }

    /**
     * The suite edits the token before replaying, so a correct server rejects it in the engine
     * before this handler is ever reached. Round 2 therefore only exists for completeness.
     */
    private ToolResult tamperedState(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(Map.of("confirm", confirmationRequest()),
                    Json.obj().put("fixture", "tampered-state"));
        }
        return ToolResult.error("a tampered requestState must never reach the handler");
    }

    private ToolResult multipleInputs(RequestContext context) {
        if (!context.isResumption()) {
            Map<String, InputRequest> requests = new LinkedHashMap<>();
            requests.put("user_name", nameRequest());
            requests.put("greeting", InputRequest.sampling(Json.obj()
                    .set("messages", Json.arr().add(Json.obj()
                            .put("role", "user")
                            .set("content", Json.obj()
                                    .put("type", "text")
                                    .put("text", "Generate a greeting"))))
                    .put("maxTokens", 50)));
            requests.put("client_roots", InputRequest.listRoots());
            return ToolResult.inputRequired(requests, Json.obj().put("fixture", "multiple-inputs"));
        }
        return ToolResult.text("All three inputs received.");
    }

    private ToolResult multiRound(RequestContext context) {
        if (!context.isResumption()) {
            return ToolResult.inputRequired(Map.of("step1", nameRequest()),
                    Json.obj().put("round", 1));
        }
        JsonNode state = context.continuationState();
        int round = state == null ? 0 : state.path("round").asInt(0);
        if (round <= 1) {
            return ToolResult.inputRequired(Map.of("step2", InputRequest.elicitation(Json.obj()
                            .put("message", "Step 2: What is your favorite color?")
                            .set("requestedSchema", Json.obj()
                                    .put("type", "object")
                                    .set("properties", Json.obj()
                                            .set("color", Json.obj().put("type", "string")))
                                    .set("required", Json.arr().add("color"))))),
                    Json.obj().put("round", 2));
        }
        return ToolResult.text("Both steps answered.");
    }

    private ToolResult capabilities(RequestContext context) {
        if (context.isResumption()) {
            return ToolResult.text("Inputs gathered for the declared capabilities.");
        }
        Map<String, InputRequest> requests = new LinkedHashMap<>();
        if (context.hasCapability("sampling")) {
            requests.put("capital_question", InputRequest.sampling(Json.obj()
                    .set("messages", Json.arr().add(Json.obj()
                            .put("role", "user")
                            .set("content", Json.obj()
                                    .put("type", "text")
                                    .put("text", "What is the capital of France?"))))
                    .put("maxTokens", 100)));
        }
        if (context.hasCapability("elicitation")) {
            requests.put("user_name", nameRequest());
        }
        if (context.hasCapability("roots")) {
            requests.put("client_roots", InputRequest.listRoots());
        }
        if (requests.isEmpty()) {
            return ToolResult.text("Client declared no input capabilities.");
        }
        return ToolResult.inputRequired(requests, Json.obj().put("fixture", "capabilities"));
    }

    // ------------------------------------------------------------------ helpers

    private static InputRequest nameRequest() {
        return InputRequest.elicitation(Json.obj()
                .put("message", "What is your name?")
                .set("requestedSchema", Json.obj()
                        .put("type", "object")
                        .set("properties", Json.obj()
                                .set("name", Json.obj().put("type", "string")))
                        .set("required", Json.arr().add("name"))));
    }

    private static InputRequest confirmationRequest() {
        return InputRequest.elicitation(Json.obj()
                .put("message", "Please confirm")
                .set("requestedSchema", Json.obj()
                        .put("type", "object")
                        .set("properties", Json.obj()
                                .set("ok", Json.obj().put("type", "boolean")))
                        .set("required", Json.arr().add("ok"))));
    }

    /** The {@code content} of a client response, or an empty object when it did not answer. */
    private static JsonNode content(RequestContext context, String inputId) {
        InputResponse response = context.input(inputId);
        if (response == null || response.content() == null) {
            return Json.obj();
        }
        return response.content();
    }
}