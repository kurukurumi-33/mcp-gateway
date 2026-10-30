package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Everything an adapter needs to know about the call it is answering.
 *
 * <p>A stateless server has no memory between rounds, so a resumed call looks like a brand-new one
 * except that {@link #inputResponses()} is populated and {@link #isResumption()} is true. Adapters
 * that use MRTR must re-read the arguments and any private state they persisted on the first round
 * rather than assuming anything is still in memory.
 *
 * @param toolName          the qualified name the client called, useful in error messages
 * @param arguments         the tool arguments; never null, empty object when the client sent none
 * @param inputResponses    answers to the previous round's {@link InputRequest}s, keyed by request id
 * @param continuationState what this handler asked the engine to remember last round, or null
 * @param clientCapabilities what the client declared in {@code _meta} for this request; never null
 * @param progressToken     client-supplied token for progress notifications, or null
 * @param logLevel          client-requested log level, or null when the client asked for no logging
 * @param emitter           sink for this request's notifications
 */
public record RequestContext(
        String toolName,
        JsonNode arguments,
        Map<String, InputResponse> inputResponses,
        JsonNode continuationState,
        JsonNode clientCapabilities,
        String progressToken,
        String logLevel,
        StreamEmitter emitter) {

    public RequestContext {
        if (arguments == null) {
            arguments = Json.obj();
        }
        if (inputResponses == null) {
            inputResponses = Map.of();
        }
        if (clientCapabilities == null) {
            clientCapabilities = Json.obj();
        }
        if (emitter == null) {
            emitter = StreamEmitter.noop();
        }
    }

    /**
     * Whether the client declared a capability for this request.
     *
     * <p>A server must not ask for anything the client did not declare, so handlers that would
     * return an {@code input_required} need to gate on this instead of assuming support.
     */
    public boolean hasCapability(String capability) {
        JsonNode node = Json.orNull(clientCapabilities, capability);
        return node != null && !node.isNull();
    }

    /** True when this call is a replay of an earlier round that returned {@code input_required}. */
    public boolean isResumption() {
        return !inputResponses.isEmpty();
    }

    /** The client's answer to a named {@link InputRequest}, or null when it did not answer that one. */
    public InputResponse input(String requestId) {
        return inputResponses.get(requestId);
    }

    /**
     * Whether log notifications may be emitted at all. The protocol forbids them unless the client
     * opted in per request, so adapters should route logging through {@link #log} rather than
     * calling the emitter directly.
     */
    public boolean loggingRequested() {
        return logLevel != null && !logLevel.isBlank();
    }

    public void progress(double progress, Double total, String message) {
        emitter.progress(progressToken, progress, total, message);
    }

    /** No-op unless the client asked for this level of logging on this call. */
    public void log(String level, String message) {
        if (loggingRequested()) {
            emitter.log(level, message);
        }
    }

    public String stringArg(String field) {
        JsonNode node = Json.orNull(arguments, field);
        return node == null ? null : node.asString();
    }

    public Integer intArg(String field) {
        JsonNode node = Json.orNull(arguments, field);
        return node == null ? null : node.asInt();
    }
}