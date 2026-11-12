package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.server.CallLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the real Lua script against a real Redis, which is the only way to prove the atomicity
 * and refill behaviour. Skipped unless {@code MCP_TEST_REDIS_HOST} is set, the same way the S3
 * tests are gated on {@code MCP_S3_ENDPOINT}.
 *
 * <pre>
 * MCP_TEST_REDIS_HOST=localhost MCP_TEST_REDIS_PORT=6379 \
 * MCP_TEST_REDIS_PASSWORD=123456 MCP_TEST_REDIS_DB=15 mvn verify
 * </pre>
 */
class RedisCallLimiterIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static String prefix;

    @BeforeAll
    static void connect() {
        String host = System.getenv("MCP_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(),
                "set MCP_TEST_REDIS_HOST to run the Redis integration test");

        int port = Integer.parseInt(System.getenv().getOrDefault("MCP_TEST_REDIS_PORT", "6379"));
        int database = Integer.parseInt(System.getenv().getOrDefault("MCP_TEST_REDIS_DB", "15"));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(database);
        String password = System.getenv("MCP_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }

        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        prefix = "mcp:test:" + UUID.randomUUID() + ":";
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("two limiter instances share one budget, and the bucket refills over time")
    void budgetIsSharedAndRefills() throws Exception {
        RedisCallLimiter a = limiter(2, 1.0);
        RedisCallLimiter b = limiter(2, 1.0);

        try {
            assertTrue(a.tryAcquire("storage").allowed(), "first token");
            assertTrue(b.tryAcquire("storage").allowed(), "the second instance sees the first spent");
            CallLimiter.Decision denied = b.tryAcquire("storage");
            assertFalse(denied.allowed(), "budget of two is exhausted across instances");
            assertTrue(denied.retryAfterMs() >= 500 && denied.retryAfterMs() <= 1_500,
                    "about one second until the next token, was " + denied.retryAfterMs());

            Thread.sleep(1_100);
            assertTrue(a.tryAcquire("storage").allowed(), "one token refilled after a second");
        } finally {
            redis.delete(prefix + "storage");
        }
    }

    @Test
    @DisplayName("each namespace has its own bucket")
    void bucketsArePerBackend() {
        RedisCallLimiter limiter = limiter(1, 1.0);
        try {
            assertTrue(limiter.tryAcquire("storage").allowed());
            assertFalse(limiter.tryAcquire("storage").allowed());
            assertTrue(limiter.tryAcquire("analytics").allowed(),
                    "draining one namespace must not touch another");
        } finally {
            redis.delete(prefix + "storage");
            redis.delete(prefix + "analytics");
        }
    }

    private static RedisCallLimiter limiter(int capacity, double refillPerSecond) {
        return new RedisCallLimiter(redis, capacity, refillPerSecond, prefix, true, 1_000, null);
    }
}