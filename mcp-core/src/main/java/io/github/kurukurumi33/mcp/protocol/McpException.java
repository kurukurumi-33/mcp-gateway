package io.github.kurukurumi33.mcp.protocol;

import tools.jackson.databind.JsonNode;

/**
 * A protocol-level failure that maps directly onto a JSON-RPC error object and an HTTP status.
 *
 * <p>These are thrown while validating or routing a request — before any backend handler runs —
 * so they always produce a structured error response rather than a tool error.
 */
public class McpException extends RuntimeException {

    private final int code;
    private final int httpStatus;
    private final JsonNode data;

    public McpException(int code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public McpException(int code, int httpStatus, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Structured payload for the error object, or null. Only some codes define one. */
    public JsonNode data() {
        return data;
    }

    public static McpException headerMismatch(String message) {
        return new McpException(McpProtocol.HEADER_MISMATCH, 400, message);
    }

    public static McpException unsupportedVersion(String message) {
        return new McpException(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, 400, message);
    }

    /**
     * Carries the versions this server does speak and the one that was asked for.
     *
     * <p>The error is defined with data rather than only a message because a client that guessed a
     * version needs to know what to retry with.
     */
    public static McpException unsupportedVersion(String message, String requested) {
        return new McpException(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, 400, message,
                Json.obj()
                        .set("supported", Json.arr().add(McpProtocol.PROTOCOL_VERSION))
                        .put("requested", requested));
    }

    public static McpException invalidRequest(String message) {
        return new McpException(McpProtocol.INVALID_REQUEST, 400, message);
    }

    public static McpException invalidParams(String message) {
        return new McpException(McpProtocol.INVALID_PARAMS, 400, message);
    }

    public static McpException methodNotFound(String message) {
        return new McpException(McpProtocol.METHOD_NOT_FOUND, 404, message);
    }

    public static McpException missingCapability(String message) {
        return new McpException(McpProtocol.MISSING_REQUIRED_CLIENT_CAPABILITY, 400, message);
    }

    /**
     * The capability the request needed and the client did not declare.
     *
     * <p>{@code requiredCapabilities} is a {@code ClientCapabilities} object, not a list of names:
     * the schema types it as {@code { "sampling": {} }}, and clients key their handling off that
     * shape.
     */
    public static McpException missingCapability(String message, JsonNode requiredCapabilities) {
        return new McpException(McpProtocol.MISSING_REQUIRED_CLIENT_CAPABILITY, 400, message,
                Json.obj().set("requiredCapabilities", requiredCapabilities));
    }
}