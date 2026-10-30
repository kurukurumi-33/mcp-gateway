package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

/**
 * A single piece of information the server needs before it can finish a call.
 *
 * <p>Under 2026-07-28 the server may no longer send {@code elicitation/create} on its own. It returns
 * one of these instead, keyed by an id the client will use when it answers.
 *
 * @param method the client-side method to run, e.g. {@code elicitation/create}
 * @param params arguments for that method, already in protocol shape
 */
public record InputRequest(String method, JsonNode params) {

    public static InputRequest elicitation(JsonNode params) {
        return new InputRequest("elicitation/create", params);
    }

    public static InputRequest sampling(JsonNode params) {
        return new InputRequest("sampling/createMessage", params);
    }

    /** {@code roots/list} takes no parameters; the client answers with its root directories. */
    public static InputRequest listRoots() {
        return new InputRequest("roots/list", Json.obj());
    }
}