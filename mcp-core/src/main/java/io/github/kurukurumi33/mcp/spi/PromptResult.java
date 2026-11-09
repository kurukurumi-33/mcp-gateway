package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * What {@code prompts/get} hands back: the messages a client should prefill.
 *
 * <p>Messages are wire-shaped blocks, the same way {@link ToolResult#content()} is. The alternative
 * would be a class hierarchy per content type, and the protocol already defines the shape: four
 * content variants today, more later, each with its own fields. The factories below cover the ones
 * this gateway can produce; anything else can be built with {@link Json} directly.
 *
 * @param description optional description shown alongside the prompt
 * @param messages    at least one message
 */
public record PromptResult(String description, List<JsonNode> messages) {

    public PromptResult {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("a prompt result must contain at least one message");
        }
        messages = List.copyOf(messages);
    }

    public static PromptResult of(JsonNode... messages) {
        return new PromptResult(null, List.of(messages));
    }

    public static PromptResult of(List<JsonNode> messages) {
        return new PromptResult(null, messages);
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