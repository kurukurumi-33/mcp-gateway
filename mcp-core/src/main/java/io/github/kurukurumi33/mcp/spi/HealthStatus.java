package io.github.kurukurumi33.mcp.spi;

/**
 * Reachability of one backend, as reported by {@link BackendAdapter#health()}.
 *
 * <p>A backend that is {@link #DOWN} keeps its tools listed but marks them unavailable, rather than
 * disappearing from {@code tools/list}. An agent that can see "this capability exists but is
 * currently unreachable" makes a better decision than one that silently loses the capability.
 *
 * @param state   coarse reachability
 * @param detail  human-readable explanation, surfaced in {@code server/discover} and actuator health
 */
public record HealthStatus(State state, String detail) {

    public enum State {
        /** Backend answered its own liveness probe. */
        UP,
        /** Backend is reachable but degraded — some operations may fail. */
        DEGRADED,
        /** Backend did not answer, or answered with an error. */
        DOWN
    }

    public static final HealthStatus UP = new HealthStatus(State.UP, "ok");

    public static HealthStatus up(String detail) {
        return new HealthStatus(State.UP, detail);
    }

    public static HealthStatus degraded(String detail) {
        return new HealthStatus(State.DEGRADED, detail);
    }

    public static HealthStatus down(String detail) {
        return new HealthStatus(State.DOWN, detail);
    }

    public boolean isUp() {
        return state == State.UP;
    }

    /** Tools backed by this adapter stay callable unless the backend is fully down. */
    public boolean isCallable() {
        return state != State.DOWN;
    }
}