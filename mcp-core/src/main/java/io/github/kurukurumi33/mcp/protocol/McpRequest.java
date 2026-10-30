package io.github.kurukurumi33.mcp.protocol;

import tools.jackson.databind.JsonNode;

/**
 * One inbound request as the transport hands it to the engine: the mirrored headers plus the body.
 *
 * <p>Headers are kept separate from the body on purpose — the 2026-07-28 revision requires the
 * server to reject a request whose headers and body disagree, and that check is impossible once
 * the two are merged.
 *
 * @param protocolVersionHeader value of {@code MCP-Protocol-Version}, or {@code null} when absent
 * @param methodHeader          value of {@code Mcp-Method}, or {@code null} when absent
 * @param nameHeader            value of {@code Mcp-Name}, or {@code null} when absent
 * @param body                  the parsed JSON-RPC request object
 */
public record McpRequest(
        String protocolVersionHeader,
        String methodHeader,
        String nameHeader,
        JsonNode body) {

    public String method() {
        JsonNode m = Json.orNull(body, "method");
        return m == null ? null : m.asString();
    }

    public JsonNode params() {
        return Json.orNull(body, "params");
    }

    /** The JSON-RPC id, echoed back on the response. Absent for notifications. */
    public JsonNode id() {
        return Json.orNull(body, "id");
    }

    /** Per-request context — the replacement for the deleted {@code initialize} handshake. */
    public JsonNode meta() {
        return Json.orNull(params(), "_meta");
    }
}