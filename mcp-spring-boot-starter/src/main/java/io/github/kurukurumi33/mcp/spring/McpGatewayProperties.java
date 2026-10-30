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
}