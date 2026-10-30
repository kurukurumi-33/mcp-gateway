package io.github.kurukurumi33.mcp.backends.s3;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import io.github.kurukurumi33.mcp.spi.InputRequest;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Object storage exposed over the S3 API.
 *
 * <p>Written against the protocol rather than against one vendor, so the same adapter talks to
 * MinIO, SeaweedFS, Ceph, Alibaba Cloud OSS or AWS itself — only the endpoint changes. Path-style
 * addressing is forced because the self-hosted implementations generally do not do virtual-host
 * buckets on a bare IP address.
 *
 * <p>Two rules from the SPI are load-bearing here: {@link #tools()} never touches the network, so a
 * storage outage does not take the tool catalog with it, and every S3 failure is turned into a
 * failed tool result rather than an exception, so a missing bucket reads as "this call failed" and
 * not as a broken protocol.
 */
public final class S3StorageAdapter implements BackendAdapter {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;

    private final String namespace;
    private final String bucket;
    private final S3Client client;

    public S3StorageAdapter(String namespace, URI endpoint, String region,
                            String accessKey, String secretKey, String bucket) {
        this.namespace = namespace;
        this.bucket = bucket;
        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                // Every call is bounded: a hung storage node must not hold a gateway thread forever.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .build())
                .build();
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String description() {
        return "Object storage over the S3 API (bucket '" + bucket + "')";
    }

    @Override
    public List<ToolDefinition> tools() {
        ObjectNode keyOnly = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj().set("key", Json.obj().put("type", "string")))
                .set("required", Json.arr().add("key"));

        ObjectNode listObjects = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("prefix", Json.obj().put("type", "string"))
                        .set("limit", Json.obj().put("type", "integer")));

        ObjectNode putText = Json.obj()
                .put("type", "object")
                .set("properties", Json.obj()
                        .set("key", Json.obj().put("type", "string"))
                        .set("content", Json.obj().put("type", "string")))
                .set("required", Json.arr().add("key").add("content"));

        return List.of(
                ToolDefinition.of("list_buckets", "Lists buckets visible to the configured credentials.",
                        Json.obj().put("type", "object")),
                ToolDefinition.of("list_objects", "Lists object keys in the configured bucket.",
                        listObjects),
                ToolDefinition.of("put_text", "Writes a UTF-8 text object to the bucket.", putText),
                ToolDefinition.of("get_text", "Reads a UTF-8 text object from the bucket.", keyOnly),
                ToolDefinition.builder("delete_object")
                        .description("Deletes an object. Asks for confirmation before doing it.")
                        .inputSchema(keyOnly)
                        .mrtr(true)
                        .build());
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        try {
            return switch (toolName) {
                case "list_buckets" -> listBuckets();
                case "list_objects" -> listObjects(context);
                case "put_text" -> putText(context);
                case "get_text" -> getText(context);
                case "delete_object" -> deleteObject(context);
                default -> ToolResult.error("Unknown tool '" + toolName + "'");
            };
        } catch (S3Exception e) {
            return ToolResult.error("S3 error " + e.statusCode() + ": " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            return ToolResult.error("Could not read the object body: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Storage call failed: " + e.getMessage());
        }
    }

    @Override
    public HealthStatus health() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return HealthStatus.UP;
        } catch (RuntimeException e) {
            return HealthStatus.down("bucket '" + bucket + "' unreachable: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tools

    private ToolResult listBuckets() {
        ArrayNode names = Json.arr();
        client.listBuckets().buckets().forEach(b -> names.add(b.name()));
        ObjectNode structured = Json.obj().set("buckets", names);
        return ToolResult.of(names.size() + " bucket(s)", structured);
    }

    private ToolResult listObjects(RequestContext context) {
        String prefix = context.stringArg("prefix");
        Integer requested = context.intArg("limit");
        int limit = requested == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, requested));

        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .maxKeys(limit);
        if (prefix != null && !prefix.isBlank()) {
            request.prefix(prefix);
        }

        ArrayNode objects = Json.arr();
        for (S3Object object : client.listObjectsV2(request.build()).contents()) {
            objects.add(Json.obj()
                    .put("key", object.key())
                    .put("size", object.size())
                    .put("lastModified", String.valueOf(object.lastModified())));
        }

        ObjectNode structured = Json.obj()
                .put("bucket", bucket)
                .put("count", objects.size())
                .set("objects", objects);
        return ToolResult.of(objects.size() + " object(s) in " + bucket, structured);
    }

    private ToolResult putText(RequestContext context) {
        String key = context.stringArg("key");
        String content = context.stringArg("content");
        if (key == null || key.isBlank()) {
            return ToolResult.error("key is required");
        }
        if (content == null) {
            content = "";
        }

        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));

        return ToolResult.of("wrote " + content.length() + " chars to " + key,
                Json.obj().put("bucket", bucket).put("key", key).put("bytes", content.length()));
    }

    private ToolResult getText(RequestContext context) throws IOException {
        String key = context.stringArg("key");
        if (key == null || key.isBlank()) {
            return ToolResult.error("key is required");
        }

        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return ToolResult.of(text, Json.obj()
                    .put("bucket", bucket)
                    .put("key", key)
                    .put("bytes", text.length()));
        }
    }

    private ToolResult deleteObject(RequestContext context) {
        String key = context.stringArg("key");
        if (key == null || key.isBlank()) {
            return ToolResult.error("key is required");
        }

        if (!context.isResumption()) {
            return ToolResult.inputRequired(
                    Map.of("confirm_delete", InputRequest.elicitation(Json.obj()
                            .put("message", "Delete '" + key + "' from " + bucket + "?")
                            .set("requestedSchema", Json.obj()
                                    .put("type", "object")
                                    .set("properties", Json.obj()
                                            .set("confirm", Json.obj().put("type", "boolean")))
                                    .set("required", Json.arr().add("confirm"))))),
                    Json.obj().put("key", key));
        }

        JsonNode state = context.continuationState();
        String stateKey = state == null ? null : state.path("key").asString(null);
        if (stateKey == null || !stateKey.equals(key)) {
            // The state names a different object than the request does: refuse rather than guess.
            return ToolResult.error("requestState does not match the key being deleted");
        }

        var confirmation = context.input("confirm_delete");
        if (confirmation == null || !confirmation.isAccept()) {
            return ToolResult.error("deletion of '" + key + "' was not confirmed");
        }

        client.deleteObject(b -> b.bucket(bucket).key(key));
        return ToolResult.of("deleted " + key,
                Json.obj().put("bucket", bucket).put("deleted", key));
    }
}