package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Everything a prompt handler needs to know about the {@code prompts/get} it is answering.
 *
 * <p>A prompt can return {@code input_required} just like a tool, so the same two-round shape
 * applies: a resumed call looks like a brand-new one except that {@link #inputResponses()} is
 * populated. The handler must re-read {@link #arguments()} and {@link #continuationState()} rather
 * than assume anything from the first round is still in memory.
 *
 * @param promptName        the unqualified name, as declared by this adapter
 * @param arguments         the caller's arguments; never null, empty object when none were sent
 * @param inputResponses    answers to the previous round's {@link InputRequest}s, keyed by request id
 * @param continuationState what this handler asked the engine to remember last round, or null
 */
public record PromptContext(
        String promptName,
        JsonNode arguments,
        Map<String, InputResponse> inputResponses,
        JsonNode continuationState) {

    public PromptContext {
        if (arguments == null) {
            arguments = Json.obj();
        }
        if (inputResponses == null) {
            inputResponses = Map.of();
        }
    }

    /** True when this call is a replay of an earlier round that returned {@code input_required}. */
    public boolean isResumption() {
        return !inputResponses.isEmpty();
    }

    /** The client's answer to a named {@link InputRequest}, or null when it did not answer that one. */
    public InputResponse input(String requestId) {
        return inputResponses.get(requestId);
    }

    public String stringArg(String field) {
        JsonNode node = Json.orNull(arguments, field);
        return node == null ? null : node.asString();
    }
}