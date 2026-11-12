package io.github.kurukurumi33.mcp.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the MCP endpoint, under {@code mcp.gateway.*}.
 */
@ConfigurationProperties(prefix = "mcp.gateway")
public class McpGatewayProperties {

    /** Whether to expose the MCP endpoint at all. */
    private boolean enabled = true;

    /** Path the endpoint is served on. */
    private String path = "/mcp";

    /** Name reported to clients in every result's {@code _meta}. */
    private String serverName = "mcp-gateway";

    private String serverVersion = "0.1.0";

    /** Freshness hint sent with {@code tools/list}, in milliseconds. */
    private long cacheTtlMs = 60_000L;

    /**
     * Key used to sign {@code requestState}. Must be at least 16 bytes and must stay the same across
     * restarts, or in-flight multi-round calls become unresumable. When blank a random key is
     * generated at startup and a warning is logged.
     */
    private String requestStateSecret;

    /** When false, everything is buffered and returned as a single JSON response. */
    private boolean streamingEnabled = true;

    /**
     * Largest request body the HTTP transport will read, in bytes. Anything bigger is answered with
     * 413 before the JSON is parsed, so a single request cannot exhaust the heap.
     */
    private int maxRequestBytes = 1_048_576;

    /**
     * How long a {@code requestState} token stays valid, in seconds. Multi-round calls are usually
     * human-in-the-loop, so this has to survive a pause for a confirmation; it also bounds how long
     * a captured token can be replayed. Must be positive.
     */
    private long requestStateTtlSeconds = 900;

    /** Calls allowed to run against one backend at once; 0 disables the bulkhead. */
    private int maxConcurrentCallsPerBackend = 16;

    /** Callers allowed to wait for a slot on one backend; only used when a queue wait is set. */
    private int maxQueuedCallsPerBackend = 32;

    /** How long a caller may wait for a backend slot before being shed with 429; 0 rejects at once. */
    private long admissionQueueWaitMs = 0L;

    /** Deadline for one adapter call, in milliseconds; 0 disables the deadline. */
    private long callTimeoutMs = 30_000L;

    /** Shared token-bucket rate limit, enforced in Redis when the app supplies a Redis client. */
    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    public String getRequestStateSecret() {
        return requestStateSecret;
    }

    public void setRequestStateSecret(String requestStateSecret) {
        this.requestStateSecret = requestStateSecret;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public long getRequestStateTtlSeconds() {
        return requestStateTtlSeconds;
    }

    public void setRequestStateTtlSeconds(long requestStateTtlSeconds) {
        this.requestStateTtlSeconds = requestStateTtlSeconds;
    }

    public int getMaxConcurrentCallsPerBackend() {
        return maxConcurrentCallsPerBackend;
    }

    public void setMaxConcurrentCallsPerBackend(int maxConcurrentCallsPerBackend) {
        this.maxConcurrentCallsPerBackend = maxConcurrentCallsPerBackend;
    }

    public int getMaxQueuedCallsPerBackend() {
        return maxQueuedCallsPerBackend;
    }

    public void setMaxQueuedCallsPerBackend(int maxQueuedCallsPerBackend) {
        this.maxQueuedCallsPerBackend = maxQueuedCallsPerBackend;
    }

    public long getAdmissionQueueWaitMs() {
        return admissionQueueWaitMs;
    }

    public void setAdmissionQueueWaitMs(long admissionQueueWaitMs) {
        this.admissionQueueWaitMs = admissionQueueWaitMs;
    }

    public long getCallTimeoutMs() {
        return callTimeoutMs;
    }

    public void setCallTimeoutMs(long callTimeoutMs) {
        this.callTimeoutMs = callTimeoutMs;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * One token bucket per adapter namespace, shared by every gateway instance through Redis.
     *
     * <p>{@code capacity} is the burst size and {@code refill-per-second} the sustained rate.
     * Turning the limiter on requires a Redis client on the classpath; the app fails to start
     * rather than silently running unlimited.
     */
    public static class RateLimit {

        private boolean enabled = false;
        private int capacity = 100;
        private double refillPerSecond = 50;
        private String keyPrefix = "mcp:gateway:rate:";

        /** When Redis is unreachable: true lets calls through, false rejects them for a second. */
        private boolean failOpen = true;

        /**
         * Budget for one limiter check. The Redis client's own timeout is often 60 seconds, which
         * would stall every call during an outage before the failure policy could apply.
         */
        private long timeoutMs = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}