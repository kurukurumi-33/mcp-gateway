package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * What a tool handler hands back to the engine.
 *
 * <p>Four shapes are possible: a normal result, a failed tool call, a request for more input, or a
 * request for more input that also reports an error. Which one you return decides the {@code resultType}
 * the client sees, so there is no way to forget it.
 *
 * @param kind             {@link Kind#COMPLETE} or {@link Kind#INPUT_REQUIRED}
 * @param content          content blocks (text, image, resource link, …)
 * @param structuredContent machine-readable output; null when the tool returns content blocks only
 * @param isError          true when the call failed; the client surfaces this to the model as a failure
 * @param inputRequests    the questions to ask, keyed by an id the client echoes back
 * @param continuationState opaque handler state carried across rounds; the engine signs it into {@code requestState}
 */
public record ToolResult(
        Kind kind,
        List<JsonNode> content,
        JsonNode structuredContent,
        boolean isError,
        Map<String, InputRequest> inputRequests,
        JsonNode continuationState) {

    public enum Kind {
        COMPLETE,
        INPUT_REQUIRED
    }

    public ToolResult {
        if (content == null) {
            content = List.of();
        }
        if (inputRequests == null) {
            inputRequests = Map.of();
        }
    }

    // ---- factories ----

    /** A successful call whose entire output is one text block. */
    public static ToolResult text(String text) {
        return new ToolResult(Kind.COMPLETE, List.of(textContent(text)), null, false, Map.of(), null);
    }

    /** A successful call that also carries machine-readable output. */
    public static ToolResult structured(JsonNode structuredContent) {
        return new ToolResult(Kind.COMPLETE, List.of(), structuredContent, false, Map.of(), null);
    }

    /** A successful call returning both a text view and structured output. */
    public static ToolResult of(String text, JsonNode structuredContent) {
        return new ToolResult(Kind.COMPLETE, List.of(textContent(text)), structuredContent, false, Map.of(), null);
    }

    /** A failed call. The failure is reported to the model, not thrown. */
    public static ToolResult error(String message) {
        return new ToolResult(Kind.COMPLETE, List.of(textContent(message)), null, true, Map.of(), null);
    }

    /**
     * Asks the client for input and ends this round.
     *
     * @param inputRequests    questions keyed by id
     * @param continuationState whatever the handler needs to remember; the engine signs and returns it
     */
    public static ToolResult inputRequired(Map<String, InputRequest> inputRequests, JsonNode continuationState) {
        if (inputRequests == null || inputRequests.isEmpty()) {
            throw new IllegalArgumentException("input_required must carry at least one InputRequest");
        }
        return new ToolResult(Kind.INPUT_REQUIRED, List.of(), null, false, inputRequests, continuationState);
    }

    /** A failed call whose only content is a message. */
    public static JsonNode textContent(String text) {
        return Json.obj().put("type", "text").put("text", text);
    }

    public boolean inputRequired() {
        return kind == Kind.INPUT_REQUIRED;
    }
}