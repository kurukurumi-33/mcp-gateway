package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.server.CallLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A token bucket kept in Redis, so every gateway instance draws from one budget.
 *
 * <p>The whole refill-and-deduct step is one Lua script, which is what makes it safe: Redis executes
 * a script atomically, so two instances can never both read "one token left" and both spend it.
 * The clock comes from Redis ({@code TIME}), not from each instance, so skew between hosts cannot
 * let a fast clock mint tokens.
 *
 * <p>One bucket per adapter namespace. Limiting per client is not an option worth offering here:
 * the client identity in {@code _meta.clientInfo} is self-reported and therefore not a trust
 * boundary.
 *
 * <p>When Redis is unreachable the limiter does not take the gateway down with it. With
 * {@code fail-open} (the default) calls proceed and an error counter is incremented; with
 * {@code fail-open: false} they are rejected with a one-second retry hint. Both are deliberate
 * choices an operator should make explicitly.
 *
 * <p>The check itself is bounded by {@code timeout-ms}. Relying on the Redis client's own command
 * timeout is not enough: Lettuce defaults to 60 seconds, so without this guard every tool call
 * would stall for a minute during a Redis outage before failing open.
 */
public final class RedisCallLimiter implements CallLimiter {

    private static final Log log = LogFactory.getLog(RedisCallLimiter.class);

    /**
     * Returns 0 when a token was taken, otherwise the number of milliseconds until one exists.
     * A single integer reply avoids deserializing a Lua table through the template's serializers.
     */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerMs = tonumber(ARGV[2])
            local ttlMs = tonumber(ARGV[3])

            -- One clock for every instance: a host with a fast clock must not mint extra tokens.
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local elapsed = now - ts
            if elapsed > 0 then
              tokens = math.min(capacity, tokens + elapsed * refillPerMs)
            end

            local wait = math.max(1, math.ceil((1 - tokens) / refillPerMs))
            if tokens >= 1 then
              tokens = tokens - 1
              wait = 0
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', key, ttlMs)
            return wait
            """;

    private static final DefaultRedisScript<Long> TAKE_TOKEN =
            new DefaultRedisScript<>(SCRIPT, Long.class);

    private static final long FAIL_CLOSED_RETRY_MS = 1_000L;

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int capacity;
    private final double refillPerMs;
    private final long ttlMs;
    private final boolean failOpen;
    private final long timeoutMs;
    private final MeterRegistry meters;
    private final ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor();

    public RedisCallLimiter(StringRedisTemplate redis, int capacity, double refillPerSecond,
                            String keyPrefix, boolean failOpen, long timeoutMs,
                            MeterRegistry meters) {
        if (capacity < 1) {
            throw new IllegalArgumentException("rate-limit capacity must be at least 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("rate-limit refill-per-second must be positive");
        }
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "mcp:gateway:rate:" : keyPrefix;
        this.failOpen = failOpen;
        this.timeoutMs = Math.max(timeoutMs, 1);
        this.meters = meters;
        // An idle bucket is full anyway, so letting the key expire simply resets to full. The
        // margin covers a bucket that is refilling while nobody calls it.
        this.ttlMs = Math.max(60_000L,
                (long) Math.ceil(capacity / refillPerSecond * 1_000) + 60_000L);
    }

    @Override
    public Decision tryAcquire(String backend) {
        Future<Decision> check;
        try {
            check = checks.submit(() -> check(backend));
        } catch (RejectedExecutionException e) {
            return whenUnavailable(backend, e);
        }
        try {
            return check.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            check.cancel(true);
            return whenUnavailable(backend, "check exceeded " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            check.cancel(true);
            Thread.currentThread().interrupt();
            return whenUnavailable(backend, "interrupted while waiting for the limiter");
        } catch (ExecutionException e) {
            return whenUnavailable(backend, e.getCause() == null ? e : e.getCause());
        }
    }

    private Decision check(String backend) {
        Long waitMs;
        try {
            waitMs = redis.execute(TAKE_TOKEN, List.of(keyPrefix + backend),
                    Integer.toString(capacity),
                    Double.toString(refillPerMs),
                    Long.toString(ttlMs));
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // The caller already gave up on this check; its own failure path counted the error.
                log.debug("Abandoned rate limiter check for backend '" + backend
                        + "' failed afterwards: " + e);
                return failOpen ? Decision.allow() : Decision.deny(FAIL_CLOSED_RETRY_MS);
            }
            return whenUnavailable(backend, e);
        }
        if (waitMs == null) {
            return whenUnavailable(backend, "script returned no result");
        }
        if (waitMs == 0) {
            return Decision.allow();
        }
        if (meters != null) {
            meters.counter("mcp.gateway.rate_limit.denied", "backend", backend).increment();
        }
        return Decision.deny(waitMs);
    }

    private Decision whenUnavailable(String backend, Object cause) {
        if (meters != null) {
            meters.counter("mcp.gateway.rate_limit.errors", "backend", backend).increment();
        }
        log.warn("Rate limiter unavailable for backend '" + backend + "': " + cause);
        return failOpen ? Decision.allow() : Decision.deny(FAIL_CLOSED_RETRY_MS);
    }
}