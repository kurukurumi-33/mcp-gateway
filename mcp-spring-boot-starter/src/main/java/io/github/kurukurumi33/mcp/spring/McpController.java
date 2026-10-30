package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.protocol.McpRequest;
import io.github.kurukurumi33.mcp.server.EngineResult;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import io.github.kurukurumi33.mcp.spi.StreamEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The single HTTP entry point: one POST, one JSON-RPC message in, one message (or one stream) out.
 *
 * <p>No session is created and nothing is stored between requests. Everything the engine needs is
 * read from the request itself — the three mirrored headers plus the body — which is what lets this
 * controller sit behind an ordinary load balancer with no sticky routing.
 *
 * <p>Streaming is decided lazily. The controller does not commit to {@code text/event-stream} before
 * the tool runs, because an invalid request must still be able to answer with a 4xx and a JSON body.
 * The first notification is what switches the response over; if none arrives, the caller gets plain
 * JSON with the correct status.
 */
@RestController
public class McpController {

    private final McpServerEngine engine;
    private final McpGatewayProperties properties;

    public McpController(McpServerEngine engine, McpGatewayProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @PostMapping("${mcp.gateway.path:/mcp}")
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       @RequestBody(required = false) String body) throws IOException {

        McpRequest mcpRequest;
        try {
            mcpRequest = new McpRequest(
                    request.getHeader(McpProtocol.HEADER_PROTOCOL_VERSION),
                    request.getHeader(McpProtocol.HEADER_METHOD),
                    request.getHeader(McpProtocol.HEADER_NAME),
                    Json.parse(body == null ? "" : body));
        } catch (McpException e) {
            // The body could not be parsed, so there is no id to echo back.
            writeJson(response, e.httpStatus(), parseError(e));
            return;
        }

        boolean wantsStream = properties.isStreamingEnabled() && acceptsEventStream(request);
        CommittingEmitter emitter = wantsStream ? new CommittingEmitter(response) : null;

        EngineResult result = engine.handle(mcpRequest, emitter);

        if (emitter != null && emitter.isCommitted()) {
            // Already streaming: the terminal message can only go out on the same stream.
            if (!result.messages().isEmpty()) {
                emitter.send(result.terminal());
            }
            return;
        }

        if (result.messages().isEmpty()) {
            response.setStatus(result.httpStatus());
            return;
        }
        writeJson(response, result.httpStatus(), result.terminal());
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private static void writeJson(HttpServletResponse response, int status, JsonNode message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Json.write(message));
    }

    private static JsonNode parseError(McpException e) {
        return Json.obj()
                .put("jsonrpc", "2.0")
                .putNull("id")
                .set("error", Json.obj()
                        .put("code", e.code())
                        .put("message", e.getMessage()));
    }

    /**
     * Commits the response to SSE on the first notification, then writes each one straight out.
     *
     * <p>Committing lazily is what keeps error handling honest: a request that fails validation never
     * emits a notification, so it still gets a proper status code instead of a 200 that turns out to
     * contain an error.
     */
    private static final class CommittingEmitter implements StreamEmitter {

        private final HttpServletResponse response;
        private PrintWriter out;

        private CommittingEmitter(HttpServletResponse response) {
            this.response = response;
        }

        @Override
        public void notify(String method, JsonNode params) {
            try {
                if (out == null) {
                    response.setStatus(200);
                    response.setContentType("text/event-stream");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setHeader("Cache-Control", "no-cache");
                    out = response.getWriter();
                }
                send(Json.obj().put("jsonrpc", "2.0").put("method", method).set("params", params));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void send(JsonNode message) {
            out.print("data: " + Json.write(message) + "\n\n");
            out.flush();
        }

        private boolean isCommitted() {
            return out != null;
        }
    }
}