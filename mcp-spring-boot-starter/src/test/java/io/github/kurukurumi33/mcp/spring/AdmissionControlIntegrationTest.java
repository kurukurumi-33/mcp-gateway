package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bulkhead over the real Spring transport: the second caller must be turned away with
 * 429 before its request reaches the backend, and the response must carry Retry-After.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestApplication.class, AdmissionControlIntegrationTest.BlockingBackend.class},
        properties = {
                "mcp.gateway.request-state-secret=integration-test-secret-0123456789",
                "mcp.gateway.max-concurrent-calls-per-backend=1",
                "mcp.gateway.admission-queue-wait-ms=0",
                "mcp.gateway.call-timeout-ms=5000"
        })
class AdmissionControlIntegrationTest {

    private static final String META =
            "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    @LocalServerPort
    int port;

    @Qualifier("gateAdapter")
    @Autowired
    GateAdapter adapter;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger ids = new AtomicInteger();

    @TestConfiguration
    static class BlockingBackend {

        @Bean
        GateAdapter gateAdapter() {
            return new GateAdapter();
        }
    }

    @Test
    @DisplayName("a call that cannot get the backend slot gets 429 with Retry-After")
    void shedsOverflowOverHttp() throws Exception {
        CompletableFuture<HttpResponse<String>> first = http.sendAsync(
                callRequest("gate_hold"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "the first call should reach the backend");
        try {
            HttpResponse<String> second = http.send(callRequest("gate_hold"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(429, second.statusCode());
            assertEquals("1", second.headers().firstValue("Retry-After").orElse(""));
            assertEquals(-32000, Json.parse(second.body()).path("error").path("code").asInt());
            assertEquals(1, adapter.executions.get(), "the shed call must not reach the backend");
        } finally {
            adapter.release.countDown();
        }

        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
    }

    private HttpRequest callRequest(String tool) {
        String params = "{\"name\":\"" + tool + "\",\"arguments\":{}," + META + "}";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + ids.incrementAndGet()
                + ",\"method\":\"tools/call\",\"params\":" + params + "}";
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", tool)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    static final class GateAdapter implements BackendAdapter {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public String namespace() {
            return "gate";
        }

        @Override
        public String description() {
            return "blocks until the test releases it";
        }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(ToolDefinition.builder("hold")
                    .description("Blocks until the test opens the gate")
                    .build());
        }

        @Override
        public ToolResult execute(String toolName, RequestContext context) {
            executions.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    return ToolResult.error("the test never released the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return ToolResult.text("released");
        }
    }
}