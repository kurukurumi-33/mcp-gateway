package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.protocol.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Spring MVC transport over real HTTP, including the lazy switch from JSON to SSE.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mcp.gateway.request-state-secret=integration-test-secret-0123456789")
class McpEndpointIntegrationTest {

    private static final String META =
            "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    @LocalServerPort
    int port;

    @Autowired
    McpBackendHealthIndicator healthIndicator;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger ids = new AtomicInteger();

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("server/discover answers over Spring MVC")
    void discover() throws Exception {
        Reply reply = post("server/discover", null, null, request("server/discover", "{" + META + "}"), false);

        assertEquals(200, reply.status());
        JsonNode result = Json.parse(reply.body()).path("result");
        assertEquals("complete", result.path("resultType").asString());
        assertEquals("2026-07-28", result.path("supportedVersions").get(0).asString());
        assertEquals("mcp-gateway", result.path("_meta")
                .path("io.modelcontextprotocol/serverInfo").path("name").asString());
    }

    @Test
    @DisplayName("tools/list contains tools contributed by every adapter bean")
    void toolsList() throws Exception {
        Reply reply = post("tools/list", null, null, request("tools/list", "{" + META + "}"), false);

        List<String> names = new ArrayList<>();
        Json.parse(reply.body()).path("result").path("tools")
                .forEach(tool -> names.add(tool.path("name").asString()));

        assertEquals(200, reply.status());
        assertTrue(names.containsAll(List.of(
                "storage_list_objects", "storage_delete_object", "compute_slow_sum")));
    }

    @Test
    @DisplayName("a plain call comes back as JSON, not SSE")
    void plainCallStaysJson() throws Exception {
        String params = "{\"name\":\"storage_list_objects\",\"arguments\":{}," + META + "}";
        Reply reply = post("tools/call", "storage_list_objects", null,
                request("tools/call", params), true); // even when the client accepts SSE

        assertTrue(reply.contentType().startsWith("application/json"), reply.contentType());
        assertEquals(2, Json.parse(reply.body()).path("result")
                .path("structuredContent").path("count").asInt());
    }

    @Test
    @DisplayName("a tool that emits progress switches the response to text/event-stream")
    void streamingSwitchesContentType() throws Exception {
        String params = "{\"name\":\"compute_slow_sum\",\"arguments\":{\"n\":3},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                + "\"progressToken\":\"tok-1\",\"io.modelcontextprotocol/logLevel\":\"info\"}}";
        Reply reply = post("tools/call", "compute_slow_sum", null,
                request("tools/call", params), true);

        assertTrue(reply.contentType().startsWith("text/event-stream"), reply.contentType());

        List<JsonNode> events = sseEvents(reply.body());
        assertEquals(7, events.size()); // 3 x (progress + log) + response
        assertEquals("notifications/progress", events.get(0).path("method").asString());
        assertEquals("complete", events.get(events.size() - 1).path("result").path("resultType").asString());
    }

    @Test
    @DisplayName("multi-round call completes over the Spring transport")
    void multiRoundTrip() throws Exception {
        String params = "{\"name\":\"storage_delete_object\",\"arguments\":{\"key\":\"a.parquet\"},"
                + META + "}";
        Reply first = post("tools/call", "storage_delete_object", null, request("tools/call", params), false);

        JsonNode round1 = Json.parse(first.body()).path("result");
        assertEquals("input_required", round1.path("resultType").asString());
        String state = round1.path("requestState").asString();
        assertFalse(state.isBlank());

        String replay = "{\"name\":\"storage_delete_object\",\"arguments\":{\"key\":\"a.parquet\"},"
                + "\"inputResponses\":{\"confirm_delete\":{\"action\":\"accept\"}},"
                + "\"requestState\":\"" + state + "\","
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}";
        Reply second = post("tools/call", "storage_delete_object", null,
                request("tools/call", replay), false);

        assertEquals("complete", Json.parse(second.body()).path("result").path("resultType").asString());
    }

    @Test
    @DisplayName("a header that disagrees with the body is rejected with -32020")
    void headerMismatch() throws Exception {
        String params = "{\"name\":\"compute_slow_sum\",\"arguments\":{\"n\":1}," + META + "}";
        Reply reply = post("tools/call", "WRONG_NAME", null, request("tools/call", params), false);

        assertEquals(400, reply.status());
        assertEquals(-32020, Json.parse(reply.body()).path("error").path("code").asInt());
    }

    @Test
    @DisplayName("actuator health aggregates every backend")
    void healthAggregatesBackends() {
        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().get("backends").toString().contains("storage"));
        assertEquals(3, health.getDetails().get("tools"));
    }

    // ------------------------------------------------------------------ plumbing

    private record Reply(int status, String contentType, String body) {}

    private Reply post(String method, String name, String version, String body, boolean acceptSse)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", version == null ? "2026-07-28" : version)
                .header("Mcp-Method", method);
        if (name != null) {
            builder.header("Mcp-Name", name);
        }
        if (acceptSse) {
            builder.header("Accept", "text/event-stream");
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private String request(String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + ids.incrementAndGet() + ",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
    }

    private static List<JsonNode> sseEvents(String body) {
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(Json.parse(line.substring(6)));
            }
        }
        return events;
    }
}