package io.github.kurukurumi33.mcp.protocol;

import tools.jackson.databind.JsonNode;

import java.util.Map;

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
    private final Map<String, String> headers;

    public McpException(int code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public McpException(int code, int httpStatus, String message, JsonNode data) {
        this(code, httpStatus, message, data, Map.of());
    }

    public McpException(int code, int httpStatus, String message, JsonNode data,
                        Map<String, String> headers) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
        this.headers = Map.copyOf(headers);
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

    /** Extra HTTP headers the transport should send alongside the error (for example Retry-After). */
    public Map<String, String> headers() {
        return headers;
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

    /**
     * The backend is already running as many calls as it is allowed to, so this one was shed before
     * it reached the backend at all. {@code Retry-After} is set because a 429 without it leaves the
     * client guessing whether to retry now or never.
     */
    public static McpException serverBusy(String backend, String operation, int limit) {
        return new McpException(McpProtocol.SERVER_BUSY, 429,
                "Backend '" + backend + "' is at its concurrency limit of " + limit
                        + "; '" + operation + "' was not started",
                Json.obj()
                        .put("backend", backend)
                        .put("operation", operation)
                        .put("reason", "concurrency")
                        .put("maxConcurrent", limit)
                        .put("retryAfterMs", 1_000),
                Map.of("Retry-After", "1"));
    }

    /**
     * The shared rate limit for this backend has no token left.
     *
     * <p>Same HTTP status and JSON-RPC code as {@link #serverBusy}: from the caller's side both mean
     * "come back later". {@code data.reason} tells an operator which of the two gates closed.
     */
    public static McpException rateLimited(String backend, String operation, long retryAfterMs) {
        long seconds = Math.max(1, (retryAfterMs + 999) / 1_000);
        return new McpException(McpProtocol.SERVER_BUSY, 429,
                "Backend '" + backend + "' is rate limited; '" + operation
                        + "' was not started, retry in about " + seconds + "s",
                Json.obj()
                        .put("backend", backend)
                        .put("operation", operation)
                        .put("reason", "rate_limit")
                        .put("retryAfterMs", retryAfterMs),
                Map.of("Retry-After", Long.toString(seconds)));
    }

    /**
     * The backend accepted the call but did not answer within the gateway's deadline.
     *
     * <p>The call may still be running: cancelling interrupts the worker thread, and a client that
     * ignores interrupts (a blocking JDBC driver, for instance) keeps its backend slot until it
     * finishes on its own.
     */
    public static McpException backendTimeout(String backend, String operation, long timeoutMs) {
        return new McpException(McpProtocol.BACKEND_TIMEOUT, 504,
                "Backend '" + backend + "' did not finish '" + operation + "' within "
                        + timeoutMs + " ms",
                Json.obj()
                        .put("backend", backend)
                        .put("operation", operation)
                        .put("timeoutMs", timeoutMs));
    }
}