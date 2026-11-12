package io.github.kurukurumi33.mcp.protocol;

import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One inbound request as the transport hands it to the engine: the HTTP headers plus the body.
 *
 * <p>Headers are kept separate from the body on purpose — the 2026-07-28 revision requires the
 * server to reject a request whose headers and body disagree, and that check is impossible once
 * the two are merged.
 *
 * <p>The whole header map is kept, not just the three required ones, because a tool may declare
 * additional mirrors through {@link McpProtocol#HEADER_PARAM_PREFIX}. Names are folded to lower
 * case on the way in: HTTP field names are case-insensitive, and so are these.
 *
 * @param headers every request header, keyed by lower-cased name
 * @param body    the parsed JSON-RPC request object
 */
public record McpRequest(Map<String, String> headers, JsonNode body) {

    public McpRequest {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    normalized.put(name.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        headers = Collections.unmodifiableMap(normalized);
    }

    /** Convenience for callers that only have the three standard headers, such as tests. */
    public static McpRequest of(String protocolVersionHeader, String methodHeader,
                                String nameHeader, JsonNode body) {
        Map<String, String> headers = new LinkedHashMap<>();
        putIfNotNull(headers, McpProtocol.HEADER_PROTOCOL_VERSION, protocolVersionHeader);
        putIfNotNull(headers, McpProtocol.HEADER_METHOD, methodHeader);
        putIfNotNull(headers, McpProtocol.HEADER_NAME, nameHeader);
        return new McpRequest(headers, body);
    }

    private static void putIfNotNull(Map<String, String> headers, String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
    }

    /** One header by name, case-insensitively, or null when absent. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String protocolVersionHeader() {
        return header(McpProtocol.HEADER_PROTOCOL_VERSION);
    }

    public String methodHeader() {
        return header(McpProtocol.HEADER_METHOD);
    }

    public String nameHeader() {
        return header(McpProtocol.HEADER_NAME);
    }

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