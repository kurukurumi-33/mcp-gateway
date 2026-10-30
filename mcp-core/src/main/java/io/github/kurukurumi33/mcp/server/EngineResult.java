package io.github.kurukurumi33.mcp.server;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The engine's answer to one request, expressed in transport-neutral terms.
 *
 * <p>A transport turns this into HTTP: {@link #sse()} chooses {@code text/event-stream} over
 * {@code application/json}, and {@link #messages()} becomes either the single response body or the
 * sequence of SSE events ending with the response.
 *
 * @param httpStatus status the transport should send
 * @param sse        true when the messages must be delivered as a stream rather than one body
 * @param messages   zero or more notifications followed by the final JSON-RPC message
 */
public record EngineResult(int httpStatus, boolean sse, List<JsonNode> messages) {

    public EngineResult {
        messages = List.copyOf(messages);
    }

    public static EngineResult json(JsonNode message) {
        return new EngineResult(200, false, List.of(message));
    }

    public static EngineResult json(JsonNode message, int httpStatus) {
        return new EngineResult(httpStatus, false, List.of(message));
    }

    public static EngineResult stream(List<JsonNode> messages) {
        return new EngineResult(200, true, messages);
    }

    /** The single terminal message, which is always the last one. */
    public JsonNode terminal() {
        return messages.get(messages.size() - 1);
    }
}