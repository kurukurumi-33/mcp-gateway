package io.github.kurukurumi33.mcp.devtools;

import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.demo.SystemAdapter;
import io.github.kurukurumi33.mcp.registry.RegisteredTool;
import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import io.github.kurukurumi33.mcp.server.RequestStateCodec;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Entry point for the standalone demo server.
 *
 * <pre>
 *   mvn -pl mcp-core exec:java
 *   mvn -pl mcp-core exec:java -Dexec.args="8931"
 * </pre>
 */
public final class DevServerMain {

    private static final int DEFAULT_PORT = 8931;

    /**
     * Fixed key so a {@code requestState} survives a restart while you are poking at the demo.
     * Override with the {@code MCP_REQUEST_STATE_SECRET} environment variable; anything real must.
     */
    private static final byte[] DEV_SECRET =
            "dev-only-request-state-key-not-for-production".getBytes(StandardCharsets.UTF_8);

    private DevServerMain() {}

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        List<BackendAdapter> adapters = List.of(
                new SystemAdapter(),
                new StorageAdapter(),
                new ComputeAdapter());

        ToolRegistry registry = new ToolRegistry(adapters);
        McpServerEngine engine = new McpServerEngine(
                registry,
                new RequestStateCodec(secret()),
                new McpServerEngine.ServerInfo("mcp-gateway-dev", "0.1.0"));

        DevHttpServer server = DevHttpServer.start("127.0.0.1", port, engine);

        System.out.println("MCP 2026-07-28 stateless server listening on http://127.0.0.1:"
                + server.port() + "/mcp");
        System.out.println("Registry: " + registry.adapters().size() + " backend(s), "
                + registry.size() + " tool(s)");
        for (RegisteredTool tool : registry.tools()) {
            System.out.println("  - " + tool.qualifiedName() + "   ["
                    + tool.adapter().namespace() + "]  " + tool.definition().description());
        }
        System.out.println("Press Ctrl+C to stop.");
    }

    private static byte[] secret() {
        String fromEnv = System.getenv("MCP_REQUEST_STATE_SECRET");
        if (fromEnv != null && fromEnv.length() >= 16) {
            return fromEnv.getBytes(StandardCharsets.UTF_8);
        }
        System.err.println("WARNING: using the built-in development signing key. "
                + "Set MCP_REQUEST_STATE_SECRET before exposing this to anyone.");
        return DEV_SECRET;
    }
}