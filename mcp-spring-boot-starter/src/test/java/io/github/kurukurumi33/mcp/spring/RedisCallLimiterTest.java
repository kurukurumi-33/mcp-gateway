package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.server.CallLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mapping from the Lua reply to a decision, and the failure policies. The script itself is
 * exercised against a real Redis in {@link RedisCallLimiterIntegrationTest}.
 */
class RedisCallLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("a zero reply takes a token")
    void allowsWhenScriptTakesToken() {
        scriptReturns(0L);

        CallLimiter.Decision decision = limiter(true).tryAcquire("storage");

        assertTrue(decision.allowed());
        assertEquals(0, meters.counter("mcp.gateway.rate_limit.denied", "backend", "storage").count());
    }

    @Test
    @DisplayName("a positive reply is the retry delay in milliseconds")
    void deniesWithRetryHint() {
        scriptReturns(2_500L);

        CallLimiter.Decision decision = limiter(true).tryAcquire("storage");

        assertFalse(decision.allowed());
        assertEquals(2_500, decision.retryAfterMs());
        assertEquals(1, meters.counter("mcp.gateway.rate_limit.denied", "backend", "storage").count());
    }

    @Test
    @DisplayName("fail-open lets the call through and counts the error")
    void failOpenOnRedisFailure() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        assertTrue(limiter(true).tryAcquire("storage").allowed());
        assertEquals(1, meters.counter("mcp.gateway.rate_limit.errors", "backend", "storage").count());
    }

    @Test
    @DisplayName("fail-closed rejects the call for a second and counts the error")
    void failClosedOnRedisFailure() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        CallLimiter.Decision decision = limiter(false).tryAcquire("storage");

        assertFalse(decision.allowed());
        assertEquals(1_000, decision.retryAfterMs());
    }

    @Test
    @DisplayName("an empty script reply is treated as a limiter failure, not as 'allowed'")
    void nullReplyUsesFailurePolicy() {
        scriptReturns(null);

        assertTrue(limiter(true).tryAcquire("storage").allowed());
        assertFalse(limiter(false).tryAcquire("storage").allowed());
    }

    @Test
    @DisplayName("nonsensical limits are rejected at construction")
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RedisCallLimiter(redis, 0, 1, "p:", true, 100, meters));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisCallLimiter(redis, 1, 0, "p:", true, 100, meters));
    }

    @Test
    @DisplayName("a check that outlives its own budget fails open instead of stalling the call")
    void slowCheckIsBounded() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0L;
                });

        long started = System.nanoTime();
        CallLimiter.Decision decision =
                new RedisCallLimiter(redis, 10, 5, "test:", true, 50, meters).tryAcquire("storage");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(decision.allowed());
        assertTrue(elapsedMs < 500, "the check must give up at 50 ms, took " + elapsedMs + " ms");
        assertEquals(1, meters.counter("mcp.gateway.rate_limit.errors", "backend", "storage").count());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void scriptReturns(Long value) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(value);
    }

    private RedisCallLimiter limiter(boolean failOpen) {
        return new RedisCallLimiter(redis, 10, 5, "test:", failOpen, 100, meters);
    }
}