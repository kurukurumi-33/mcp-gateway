package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sink for notifications that belong to the request currently being handled.
 *
 * <p>2026-07-28 draws a hard line between notification kinds. Progress and log messages must travel
 * on the <em>request's own</em> response stream — the server may not open a side channel for them, and
 * may not treat them as a way to ask the client anything (that is {@code input_required}'s job).
 */
@FunctionalInterface
public interface StreamEmitter {

    /** Emits a {@code notifications/…} message on this request's stream. */
    void notify(String method, JsonNode params);

    /**
     * Reports progress. Silently dropped when the client sent no progress token, as the protocol
     * does not define a default one.
     */
    default void progress(String progressToken, double progress, Double total, String message) {
        if (progressToken == null) {
            return;
        }
        ObjectNode params = Json.obj();
        params.put("progressToken", progressToken);
        params.put("progress", progress);
        if (total != null) {
            params.put("total", total);
        }
        if (message != null) {
            params.put("message", message);
        }
        notify("notifications/progress", params);
    }

    /**
     * Emits a log message. The caller is responsible for having checked that the client actually
     * requested logging — see {@link RequestContext#logLevel()}.
     */
    default void log(String level, String message) {
        notify("notifications/message",
                Json.obj().put("level", level).put("data", message));
    }

    /** Discards everything; used when a tool is invoked outside a streaming transport. */
    static StreamEmitter noop() {
        return (method, params) -> {};
    }
}