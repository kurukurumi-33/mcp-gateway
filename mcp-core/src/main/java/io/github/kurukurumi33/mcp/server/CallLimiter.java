package io.github.kurukurumi33.mcp.server;

/**
 * A shared rate limit in front of a backend, consulted once per call.
 *
 * <p>The gateway only needs a yes/no and a retry hint, so this deliberately says nothing about how
 * the limit is stored. A single-process implementation could count in memory; the useful one counts
 * in Redis so that four gateway instances share one budget instead of each allowing the full rate.
 *
 * <p>Implementations must not throw: a limiter that cannot reach its store has to make the
 * fail-open or fail-closed decision itself and return it.
 */
public interface CallLimiter {

    /**
     * Takes one token for {@code backend} if one is available.
     *
     * @param backend the adapter namespace the call would go to
     */
    Decision tryAcquire(String backend);

    /**
     * @param allowed     whether the call may proceed
     * @param retryAfterMs how long until a token is likely to exist; only meaningful when denied
     */
    record Decision(boolean allowed, long retryAfterMs) {

        public Decision {
            retryAfterMs = Math.max(retryAfterMs, 0);
        }

        public static Decision allow() {
            return new Decision(true, 0);
        }

        public static Decision deny(long retryAfterMs) {
            return new Decision(false, retryAfterMs);
        }
    }
}