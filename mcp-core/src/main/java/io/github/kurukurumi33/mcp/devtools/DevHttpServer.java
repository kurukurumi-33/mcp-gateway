package io.github.kurukurumi33.mcp.devtools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.server.EngineResult;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Streamable HTTP transport built on the JDK's own server, for local runs and tests only.
 *
 * <p>The real deployment binds the same {@link McpServerEngine} to Spring MVC. This exists so the
 * engine can be exercised end to end with a single {@code java -jar} and no framework — useful for
 * reproducing a protocol bug in isolation. Not hardened: no TLS, no auth, no limits.
 */
public final class DevHttpServer implements AutoCloseable {

    private final HttpServer server;

    private DevHttpServer(HttpServer server) {
        this.server = server;
    }

    /** Starts the server with per-request logging on, which is what you want when poking at it by hand. */
    public static DevHttpServer start(String host, int port, McpServerEngine engine) {
        return start(host, port, engine, true);
    }

    /**
     * @param port    the port to bind; 0 picks a free one, readable afterwards from {@link #port()}
     * @param verbose when true, each request is logged with its method, target and status
     */
    public static DevHttpServer start(String host, int port, McpServerEngine engine, boolean verbose) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/mcp", exchange -> handle(exchange, engine, verbose));
            server.setExecutor(null);
            server.start();
            return new DevHttpServer(server);
        } catch (IOException e) {
            throw new IllegalStateException("Could not bind " + host + ":" + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange, McpServerEngine engine, boolean verbose)
            throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                // 2026-07-28 removed the standalone GET stream; streaming happens on the POST itself.
                write(exchange, 405, "application/json",
                        Json.obj().put("error", "Only POST is supported on /mcp").toString());
                log(verbose, exchange, null, 405);
                return;
            }
            McpRequest request = read(exchange);
            EngineResult result = engine.handle(request);
            respond(exchange, result);
            log(verbose, exchange, request, result.httpStatus());
        }
    }

    private static void log(boolean verbose, HttpExchange exchange, McpRequest request, int status) {
        if (!verbose) {
            return;
        }
        String name = exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_NAME);
        System.out.println("  [server] " + exchange.getRequestMethod() + " "
                + (request == null || request.method() == null ? "?" : request.method())
                + (name == null ? "" : " name=" + name)
                + " -> " + status);
    }

    private static McpRequest read(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new McpRequest(
                exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_PROTOCOL_VERSION),
                exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_METHOD),
                exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_NAME),
                Json.parse(body));
    }

    private static void respond(HttpExchange exchange, EngineResult result) throws IOException {
        if (result.messages().isEmpty()) {
            exchange.sendResponseHeaders(result.httpStatus(), -1);
            return;
        }
        if (result.sse()) {
            StringBuilder events = new StringBuilder();
            for (JsonNode message : result.messages()) {
                events.append("data: ").append(Json.write(message)).append("\n\n");
            }
            write(exchange, result.httpStatus(), "text/event-stream", events.toString());
            return;
        }
        write(exchange, result.httpStatus(), "application/json",
                Json.write(result.terminal()));
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}