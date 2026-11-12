package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * What {@code prompts/get} hands back: either the messages a client should prefill, or a request for
 * input the client has to answer before the prompt can be rendered.
 *
 * <p>Messages are wire-shaped blocks, the same way {@link ToolResult#content()} is. The alternative
 * would be a class hierarchy per content type, and the protocol already defines the shape: four
 * content variants today, more later, each with its own fields. The factories below cover the ones
 * this gateway can produce; anything else can be built with {@link Json} directly.
 *
 * <p>The two kinds are exclusive and the compact constructor enforces it: a complete result has
 * messages and no pending requests, an {@code input_required} result has requests and no messages.
 *
 * @param kind              which of the two result shapes this is
 * @param description       optional description shown alongside the prompt
 * @param messages          at least one message when {@code kind} is {@link Kind#COMPLETE}
 * @param inputRequests     at least one request when {@code kind} is {@link Kind#INPUT_REQUIRED}
 * @param continuationState private state the engine signs into {@code requestState}, or null
 */
public record PromptResult(
        Kind kind,
        String description,
        List<JsonNode> messages,
        Map<String, InputRequest> inputRequests,
        JsonNode continuationState) {

    public enum Kind { COMPLETE, INPUT_REQUIRED }

    public PromptResult {
        if (kind == null) {
            throw new IllegalArgumentException("a prompt result must declare its kind");
        }
        if (kind == Kind.INPUT_REQUIRED) {
            if (inputRequests == null || inputRequests.isEmpty()) {
                throw new IllegalArgumentException("an input_required prompt result must carry at least one input request");
            }
            inputRequests = Map.copyOf(inputRequests);
            messages = List.of();
        } else {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("a complete prompt result must contain at least one message");
            }
            messages = List.copyOf(messages);
            inputRequests = Map.of();
            continuationState = null;
        }
    }

    public boolean inputRequired() {
        return kind == Kind.INPUT_REQUIRED;
    }

    public static PromptResult of(JsonNode... messages) {
        return of(null, List.of(messages));
    }

    public static PromptResult of(List<JsonNode> messages) {
        return of(null, messages);
    }

    public static PromptResult of(String description, List<JsonNode> messages) {
        return new PromptResult(Kind.COMPLETE, description, messages, Map.of(), null);
    }

    /** Asks the client for input before the prompt can be rendered. */
    public static PromptResult inputRequired(Map<String, InputRequest> inputRequests, JsonNode continuationState) {
        return new PromptResult(Kind.INPUT_REQUIRED, null, List.of(), inputRequests, continuationState);
    }

    /** A user message containing text. */
    public static JsonNode user(String text) {
        return message("user", Json.obj().put("type", "text").put("text", text));
    }

    /** An assistant message containing text. Used to prefill the model's reply. */
    public static JsonNode assistant(String text) {
        return message("assistant", Json.obj().put("type", "text").put("text", text));
    }

    /** A user message carrying a resource from this same backend. */
    public static JsonNode userWithResource(String uri, String mimeType, String text) {
        return message("user", Json.obj()
                .put("type", "resource")
                .set("resource", Json.obj()
                        .put("uri", uri)
                        .put("mimeType", mimeType)
                        .put("text", text)));
    }

    private static JsonNode message(String role, JsonNode content) {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("prompt message role must be 'user' or 'assistant', got '" + role + "'");
        }
        return Json.obj().put("role", role).set("content", content);
    }
}