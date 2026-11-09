package io.github.kurukurumi33.mcp.demo;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.PromptArgument;
import io.github.kurukurumi33.mcp.spi.PromptDefinition;
import io.github.kurukurumi33.mcp.spi.PromptResult;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ResourceContent;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A backend with no external dependency, used to show that a tool need not touch the network. */
public final class SystemAdapter implements BackendAdapter {

    private static final JsonNode PING_SCHEMA = Json.obj().put("type", "object");
    private static final String ABOUT_URI = "gateway/about.md";
    private static final String ABOUT_TEXT = """
            # MCP Gateway

            This process terminates the MCP 2026-07-28 stateless revision and forwards each call to
            one of the backends listed under `server/discover`. It keeps no per-client state: any
            value that must survive between HTTP requests travels in the signed `requestState` token.

            Tools are namespaced, resources are published under `mcp://<namespace>/...`, and a
            backend that is down stays in the catalog while its calls fail fast.
            """;

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
    public List<ResourceDefinition> resources() {
        return List.of(new ResourceDefinition(ABOUT_URI, "About this gateway", "text/markdown",
                "Operator notes on the stateless revision and how backends are namespaced."));
    }

    @Override
    public Optional<ResourceContent> readResource(String uri) {
        if (!ABOUT_URI.equals(uri)) {
            return Optional.empty();
        }
        return Optional.of(ResourceContent.text(uri, "text/markdown", ABOUT_TEXT));
    }

    @Override
    public List<PromptDefinition> prompts() {
        return List.of(new PromptDefinition("explain_backend",
                "Asks for a walkthrough of one backend's behaviour.",
                List.of(PromptArgument.required("namespace",
                        "Namespace of the backend to explain, e.g. 'storage'"))));
    }

    @Override
    public Optional<PromptResult> getPrompt(String promptName, JsonNode arguments) {
        if (!"explain_backend".equals(promptName)) {
            return Optional.empty();
        }
        String namespace = arguments.path("namespace").asString("");
        return Optional.of(new PromptResult(
                "Walkthrough of the '" + namespace + "' backend",
                List.of(PromptResult.user(
                        "Explain how the MCP gateway routes calls to the '" + namespace
                                + "' backend: which tools, resources and failure modes a caller sees."))));
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        if (!"ping".equals(toolName)) {
            return ToolResult.error("system has no tool named '" + toolName + "'");
        }
        return ToolResult.of("pong", Json.obj().put("serverTime", Instant.now().toString()));
    }
}