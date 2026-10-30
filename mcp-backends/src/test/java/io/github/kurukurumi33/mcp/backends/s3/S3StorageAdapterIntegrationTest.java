package io.github.kurukurumi33.mcp.backends.s3;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.InputResponse;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.StreamEmitter;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the adapter against a real S3 endpoint — SeaweedFS, MinIO, Ceph or the cloud.
 *
 * <p>Skipped unless {@code MCP_S3_ENDPOINT} is set:
 *
 * <pre>
 * MCP_S3_ENDPOINT=http://127.0.0.1:8333 \
 * MCP_S3_ACCESS_KEY=mcpaccess MCP_S3_SECRET_KEY=mcpsecretmcpsecret \
 * mvn -pl mcp-backends test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MCP_S3_ENDPOINT", matches = ".+")
class S3StorageAdapterIntegrationTest {

    private S3StorageAdapter adapter;
    private String bucket;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("MCP_S3_ENDPOINT");
        String accessKey = env("MCP_S3_ACCESS_KEY", "mcpaccess");
        String secretKey = env("MCP_S3_SECRET_KEY", "mcpsecretmcpsecret");
        bucket = env("MCP_S3_BUCKET", "mcp-demo");

        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build()) {
            try {
                admin.createBucket(b -> b.bucket(bucket));
            } catch (S3Exception ignored) {
                // Already exists — the test is re-runnable.
            }
        }

        adapter = new S3StorageAdapter("storage", URI.create(endpoint), "us-east-1",
                accessKey, secretKey, bucket);
    }

    @Test
    @DisplayName("a text object written through the adapter can be read back")
    void putThenGetRoundTrips() {
        String key = "probe/hello.txt";

        ToolResult written = adapter.execute("put_text",
                context(Json.obj().put("key", key).put("content", "hello gateway")));
        assertFalse(written.isError(), firstText(written));

        ToolResult read = adapter.execute("get_text", context(Json.obj().put("key", key)));
        assertFalse(read.isError(), firstText(read));
        assertEquals("hello gateway", firstText(read));
    }

    @Test
    @DisplayName("list_objects reports the object and its size")
    void listObjectsSeesTheUpload() {
        String key = "probe/listed.txt";
        adapter.execute("put_text", context(Json.obj().put("key", key).put("content", "0123456789")));

        ToolResult listed = adapter.execute("list_objects",
                context(Json.obj().put("prefix", "probe/")));

        assertFalse(listed.isError(), firstText(listed));
        JsonNode objects = listed.structuredContent().path("objects");
        assertTrue(objects.size() >= 1);
        boolean found = objects.valueStream()
                .anyMatch(o -> key.equals(o.path("key").asString()));
        assertTrue(found, "expected " + key + " in the listing");
    }

    @Test
    @DisplayName("delete takes two rounds and only removes on an accepted confirmation")
    void deleteNeedsConfirmation() {
        String key = "probe/to-delete.txt";
        adapter.execute("put_text", context(Json.obj().put("key", key).put("content", "bye")));

        ToolResult round1 = adapter.execute("delete_object", context(Json.obj().put("key", key)));
        assertTrue(round1.inputRequired(), "expected a confirmation request");
        assertEquals(key, round1.continuationState().path("key").asString());

        ToolResult declined = adapter.execute("delete_object",
                context(Json.obj().put("key", key),
                        Map.of("confirm_delete", new InputResponse("decline", Json.obj())),
                        round1.continuationState()));
        assertTrue(declined.isError(), "a declined confirmation must not delete");

        ToolResult accepted = adapter.execute("delete_object",
                context(Json.obj().put("key", key),
                        Map.of("confirm_delete", new InputResponse("accept", Json.obj()
                                .put("confirm", true))),
                        round1.continuationState()));
        assertFalse(accepted.isError(), firstText(accepted));

        ToolResult after = adapter.execute("get_text", context(Json.obj().put("key", key)));
        assertTrue(after.isError(), "the object should be gone");
    }

    // ------------------------------------------------------------------ helpers

    private static RequestContext context(ObjectNode arguments) {
        return context(arguments, Map.of(), null);
    }

    private static RequestContext context(ObjectNode arguments,
                                          Map<String, InputResponse> responses,
                                          JsonNode state) {
        return new RequestContext("storage_tool", arguments, responses, state,
                Json.obj(), null, null, StreamEmitter.noop());
    }

    private static String firstText(ToolResult result) {
        return result.content().isEmpty() ? "" : result.content().get(0).path("text").asString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}