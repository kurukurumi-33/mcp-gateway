package io.github.kurukurumi33.mcp.spi;

import tools.jackson.databind.JsonNode;

/**
 * The client's answer to one {@link InputRequest}, replayed on the retried request.
 *
 * @param action  {@code accept}, {@code decline} or {@code cancel}
 * @param content the answer payload, in whatever shape the matching {@link InputRequest} asked for
 */
public record InputResponse(String action, JsonNode content) {

    public boolean isAccept() {
        return "accept".equals(action);
    }

    public boolean isDecline() {
        return "decline".equals(action);
    }

    public boolean isCancel() {
        return "cancel".equals(action);
    }
}