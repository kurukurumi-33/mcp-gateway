package io.github.kurukurumi33.mcp.devtools;

import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.demo.SystemAdapter;
import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import io.github.kurukurumi33.mcp.server.RequestStateCodec;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the whole 2026-07-28 surface against a live server and prints what happens at each step.
 *
 * <p>The server runs in this same process on an ephemeral port, but every call goes over real HTTP —
 * nothing here reaches into the engine directly, so what you see is what a client would see on the wire.
 *
 * <pre>
 *   mvn -pl mcp-core exec:java -Dexec.mainClass=io.github.kurukurumi33.mcp.devtools.DevClientMain
 * </pre>
 */
public final class DevClientMain {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private DevClientMain() {}

    public static void main(String[] args) throws Exception {
        List<BackendAdapter> adapters = List.of(
                new SystemAdapter(),
                new StorageAdapter(),
                new ComputeAdapter());

        ToolRegistry registry = new ToolRegistry(adapters);
        McpServerEngine engine = new McpServerEngine(
                registry,
                new RequestStateCodec("demo-only-signing-key-0123456789".getBytes(StandardCharsets.UTF_8)),
                new McpServerEngine.ServerInfo("mcp-gateway-demo", "0.1.0"));

        try (DevHttpServer server = DevHttpServer.start("127.0.0.1", 0, engine, false)) {
            String endpoint = "http://127.0.0.1:" + server.port() + "/mcp";

            banner(endpoint, registry);
            discover(endpoint);
            toolsList(endpoint);
            normalCall(endpoint);
            multiRoundTrip(endpoint);
            streaming(endpoint);
            errorCases(endpoint);

            System.out.println();
            System.out.println("=".repeat(74));
            System.out.println(" Done. No initialize handshake, no session id, anywhere in the run.");
        }
    }

    // ------------------------------------------------------------------ scenarios

    private static void banner(String endpoint, ToolRegistry registry) {
        System.out.println("=".repeat(74));
        System.out.println(" MCP 2026-07-28 stateless protocol - end-to-end walkthrough");
        System.out.println(" server : " + endpoint);
        System.out.println(" backends: " + registry.adapters().size() + "   tools: " + registry.size());
        System.out.println("=".repeat(74));
    }

    private static void discover(String endpoint) throws Exception {
        section(1, "server/discover", "mandatory since 2026-07-28; replaced the initialize handshake");

        Reply reply = post(endpoint, "server/discover", null, null,
                params(null));

        JsonNode result = result(reply);
        System.out.println("   resultType        : " + result.path("resultType").asString());
        System.out.println("   supportedVersions : " + Json.write(result.path("supportedVersions")));
        System.out.println("   serverInfo        : "
                + Json.write(result.path("_meta").path(McpProtocol.META_SERVER_INFO)));
        System.out.println("   backends:");
        for (JsonNode backend : result.path("capabilities").path("io.github.kurukurumi33.mcp/backends")) {
            System.out.printf("     - %-9s health=%-8s tools=%d%n",
                    backend.path("namespace").asString(),
                    backend.path("health").asString(),
                    backend.path("tools").asInt());
        }
    }

    private static void toolsList(String endpoint) throws Exception {
        section(2, "tools/list", "every backend's tools land in one flat, namespaced catalog");

        JsonNode result = result(post(endpoint, "tools/list", null, null, params(null)));
        System.out.println("   resultType: " + result.path("resultType").asString()
                + "   ttlMs: " + result.path("ttlMs").asLong()
                + "   cacheScope: " + result.path("cacheScope").asString());
        for (JsonNode tool : result.path("tools")) {
            System.out.printf("     - %-22s available=%-5s backend=%s%n",
                    tool.path("name").asString(),
                    tool.path("_meta").path("io.github.kurukurumi33.mcp/available").asBoolean(),
                    tool.path("_meta").path("io.github.kurukurumi33.mcp/backend").asString());
        }
        System.out.println("   (the underscore prefix is the gateway's; backends name their tools freely)");
    }

    private static void normalCall(String endpoint) throws Exception {
        section(3, "tools/call - a plain call", "returns content blocks plus structuredContent");

        ObjectNode p = params("storage_list_objects");
        p.set("arguments", Json.obj());

        JsonNode result = result(post(endpoint, "tools/call", "storage_list_objects", null, p));
        System.out.println("   resultType        : " + result.path("resultType").asString());
        System.out.println("   content[0].text   : " + result.path("content").get(0).path("text").asString());
        System.out.println("   structuredContent : " + Json.write(result.path("structuredContent")));
    }

    private static void multiRoundTrip(String endpoint) throws Exception {
        section(4, "multi-round request", "the server cannot ask the client anything; it ends the round instead");

        ObjectNode first = params("storage_delete_object");
        first.set("arguments", Json.obj().put("key", "reports/2026-q3.parquet"));

        JsonNode round1 = result(post(endpoint, "tools/call", "storage_delete_object", null, first));
        System.out.println("   round 1 [resultType=" + round1.path("resultType").asString() + "]");
        System.out.println("     asks      : " + round1.path("inputRequests")
                .path("confirm_delete").path("params").path("message").asString());
        String state = round1.path("requestState").asString();
        System.out.println("     state     : " + state.substring(0, Math.min(52, state.length())) + "...");
        System.out.println("     (base64url(payload).base64url(HMAC) - the client only ever stores it)");

        ObjectNode replay = params("storage_delete_object");
        replay.set("arguments", Json.obj().put("key", "reports/2026-q3.parquet"));
        replay.set("inputResponses", Json.obj().set("confirm_delete",
                Json.obj().put("action", "accept").set("content", Json.obj().put("confirm", true))));
        replay.put("requestState", state);

        JsonNode round2 = result(post(endpoint, "tools/call", "storage_delete_object", null, replay));
        System.out.println("   round 2 [resultType=" + round2.path("resultType").asString() + "]");
        System.out.println("     content   : " + round2.path("content").get(0).path("text").asString());
        System.out.println("     (same tool, same arguments, new JSON-RPC id - the state is what links them)");
    }

    private static void streaming(String endpoint) throws Exception {
        section(5, "streaming", "progress and logs ride the request's own response stream");

        ObjectNode p = params("compute_slow_sum");
        p.set("arguments", Json.obj().put("n", 3));
        ((ObjectNode) p.get("_meta")).put("progressToken", "tok-1");
        ((ObjectNode) p.get("_meta")).put(McpProtocol.META_LOG_LEVEL, "info");

        Reply reply = post(endpoint, "tools/call", "compute_slow_sum", null, p);
        System.out.println("   Content-Type: " + reply.contentType() + "  (events: " + reply.events().size() + ")");
        for (JsonNode event : reply.events()) {
            if (event.has("method")) {
                System.out.printf("     %-24s %s%n", event.path("method").asString(),
                        Json.write(event.path("params")));
            } else {
                System.out.println("     response                 "
                        + event.path("result").path("content").get(0).path("text").asString());
            }
        }

        ObjectNode quiet = params("compute_slow_sum");
        quiet.set("arguments", Json.obj().put("n", 3));
        Reply noOptIn = post(endpoint, "tools/call", "compute_slow_sum", null, quiet);
        System.out.println("   without progressToken/logLevel in _meta -> "
                + noOptIn.events().size() + " notifications, plain JSON body");
        System.out.println("   (the protocol forbids log notifications the client did not ask for)");
    }

    private static void errorCases(String endpoint) throws Exception {
        section(6, "rejections", "what the transport layer is required to refuse");

        // Mcp-Name header contradicting the body
        ObjectNode p = params("system_ping");
        p.set("arguments", Json.obj());
        report("Mcp-Name header says WRONG_NAME, body says system_ping",
                post(endpoint, "tools/call", "WRONG_NAME", null, p));

        // protocol revision the server does not speak
        report("client asks for protocol 2019-01-01",
                post(endpoint, "tools/list", null, "2019-01-01", params(null)));

        // a method this revision deleted
        report("client calls resources/subscribe (removed in 2026-07-28)",
                post(endpoint, "resources/subscribe", null, null, params(null)));

        // forged requestState: take a real token, swap the payload, keep the signature intact
        ObjectNode prep = params("storage_delete_object");
        prep.set("arguments", Json.obj().put("key", "victim.parquet"));
        String realToken = result(post(endpoint, "tools/call", "storage_delete_object", null, prep))
                .path("requestState").asString();
        String signature = realToken.substring(realToken.indexOf('.') + 1);

        ObjectNode forgedParams = params("storage_delete_object");
        forgedParams.set("arguments", Json.obj().put("key", "victim.parquet"));
        forgedParams.put("requestState", forgePayload("{\"key\":\"somebody-elses.parquet\"}", signature));
        report("client rewrites the object name inside requestState and keeps the original signature",
                post(endpoint, "tools/call", "storage_delete_object", null, forgedParams));

        // missing version header
        report("client omits the MCP-Protocol-Version header",
                postWithoutVersionHeader(endpoint));
    }

    private static void report(String what, Reply reply) {
        System.out.println();
        System.out.println("   > " + what);
        if (reply.body() != null && reply.body().has("error")) {
            System.out.printf("     HTTP %d  code %d  %s%n",
                    reply.status(),
                    reply.body().path("error").path("code").asInt(),
                    reply.body().path("error").path("message").asString());
        } else {
            System.out.println("     HTTP " + reply.status() + "  " + Json.write(reply.body()));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Reply post(String endpoint, String method, String nameHeader,
                              String version, JsonNode params) throws Exception {
        ObjectNode body = Json.obj().put("jsonrpc", "2.0").put("id", nextId()).put("method", method);
        if (params != null) {
            body.set("params", params);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpProtocol.HEADER_METHOD, method)
                .header(McpProtocol.HEADER_PROTOCOL_VERSION,
                        version == null ? McpProtocol.PROTOCOL_VERSION : version)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));

        if (nameHeader != null) {
            builder.header(McpProtocol.HEADER_NAME, nameHeader);
        }
        return send(builder.build());
    }

    private static Reply postWithoutVersionHeader(String endpoint) throws Exception {
        ObjectNode body = Json.obj().put("jsonrpc", "2.0").put("id", nextId())
                .put("method", "tools/list").set("params", params(null));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header(McpProtocol.HEADER_METHOD, "tools/list")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private static Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String contentType = response.headers().firstValue("Content-Type").orElse("");

        if (contentType.contains("text/event-stream")) {
            List<JsonNode> events = new ArrayList<>();
            for (String line : response.body().split("\n")) {
                if (line.startsWith("data: ")) {
                    events.add(Json.parse(line.substring(6)));
                }
            }
            return new Reply(response.statusCode(), contentType, null, events);
        }
        return new Reply(response.statusCode(), contentType, Json.parse(response.body()), List.of());
    }

    private static ObjectNode params(String toolName) {
        ObjectNode params = Json.obj();
        if (toolName != null) {
            params.put("name", toolName);
        }
        params.set("_meta", Json.obj()
                .put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.PROTOCOL_VERSION)
                .set(McpProtocol.META_CLIENT_CAPABILITIES, Json.obj())
                .set(McpProtocol.META_CLIENT_INFO, Json.obj()
                        .put("name", "mcp-gateway-demo-client").put("version", "0.1.0")));
        return params;
    }

    /** Re-encodes a different payload, keeping a signature lifted from a genuine token. */
    private static String forgePayload(String payloadJson, String realSignature) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return payload + "." + realSignature;
    }

    private static JsonNode result(Reply reply) {
        if (reply.body() == null || !reply.body().has("result")) {
            throw new IllegalStateException("expected a result but got HTTP " + reply.status()
                    + " " + Json.write(reply.body()));
        }
        return reply.body().path("result");
    }

    private static void section(int number, String title, String note) {
        System.out.println();
        System.out.println("-".repeat(74));
        System.out.println(" " + number + ". " + title);
        System.out.println("    " + note);
        System.out.println("-".repeat(74));
    }

    private static int nextId() {
        return IDS.incrementAndGet();
    }

    private static final java.util.concurrent.atomic.AtomicInteger IDS =
            new java.util.concurrent.atomic.AtomicInteger();

    private record Reply(int status, String contentType, JsonNode body, List<JsonNode> events) {}
}