package io.github.kurukurumi33.mcp.demo;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** A backend with no external dependency, used to show that a tool need not touch the network. */
public final class SystemAdapter implements BackendAdapter {

    private static final JsonNode PING_SCHEMA = Json.obj().put("type", "object");

    @Override
    public String namespace() {
        return "system";
    }

    @Override
    public String description() {
        return "Gateway self-checks that never leave the process";
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(ToolDefinition.of(
                "ping",
                "Returns the gateway's current time. Use to confirm the gateway is reachable.",
                PING_SCHEMA));
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        if (!"ping".equals(toolName)) {
            return ToolResult.error("system has no tool named '" + toolName + "'");
        }
        return ToolResult.of("pong", Json.obj().put("serverTime", Instant.now().toString()));
    }
}