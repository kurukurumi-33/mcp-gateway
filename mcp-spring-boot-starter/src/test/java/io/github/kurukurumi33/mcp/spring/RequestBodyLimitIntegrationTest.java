package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.protocol.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The body ceiling is enforced by the transport, so this drives it over real HTTP.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.gateway.request-state-secret=integration-test-secret-0123456789",
                "mcp.gateway.max-request-bytes=1024"
        })
class RequestBodyLimitIntegrationTest {

    private static final int LIMIT = 1024;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("a body with Content-Length over the limit gets 413 before parsing")
    void rejectsDeclaredOversize() throws Exception {
        HttpResponse<String> reply = send(HttpRequest.BodyPublishers.ofString(body(LIMIT * 4)));

        assertEquals(413, reply.statusCode());
        JsonNode error = Json.parse(reply.body()).path("error");
        assertEquals(-32600, error.path("code").asInt());
        assertEquals(LIMIT, error.path("data").path("maxBytes").asInt());
    }

    @Test
    @DisplayName("a chunked body with no Content-Length is counted as it is read")
    void rejectsChunkedOversize() throws Exception {
        HttpResponse<String> reply = send(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body(LIMIT * 4).getBytes(StandardCharsets.UTF_8))));

        assertEquals(413, reply.statusCode());
        assertEquals(-32600, Json.parse(reply.body()).path("error").path("code").asInt());
    }

    @Test
    @DisplayName("a body exactly at the limit is accepted")
    void acceptsBodyAtLimit() throws Exception {
        HttpResponse<String> reply = send(HttpRequest.BodyPublishers.ofString(body(LIMIT)));

        assertEquals(200, reply.statusCode());
        assertEquals("complete", Json.parse(reply.body())
                .path("result").path("resultType").asString());
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher publisher) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/list")
                .POST(publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String body(int targetBytes) {
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},"
                + "\"pad\":\"";
        String suffix = "\"}}";
        int padding = targetBytes - prefix.length() - suffix.length();
        if (padding < 0) {
            throw new IllegalArgumentException("target is smaller than a valid request");
        }
        return prefix + "x".repeat(padding) + suffix;
    }
}