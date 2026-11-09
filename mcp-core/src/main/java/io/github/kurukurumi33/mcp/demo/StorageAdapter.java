package io.github.kurukurumi33.mcp.demo;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.InputRequest;
import io.github.kurukurumi33.mcp.spi.InputResponse;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ResourceContent;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;
import io.github.kurukurumi33.mcp.spi.ResourceTemplate;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stands in for an object store such as MinIO.
 *
 * <p>{@code delete_object} is the MRTR example. It cannot ask the client anything directly, so it ends
 * the round with {@code input_required} and the object name tucked into its private continuation
 * state. On the replay it reads that state back — the process may have restarted in between, which is
 * exactly why the state travels with the request instead of sitting in a field.
 */
public final class StorageAdapter implements BackendAdapter {

    private static final List<String> OBJECTS = List.of("reports/2026-q3.parquet", "raw/events-0001.jsonl");

    /** Two objects with different shapes, so the demo exercises both resource content variants. */
    private static final Map<String, byte[]> CONTENTS = Map.of(
            "reports/2026-q3.parquet",
            new byte[] {'P', 'A', 'R', '1', 0, 0, 0, 0},
            "raw/events-0001.jsonl",
            "{\"event\":\"login\",\"user\":\"demo\"}\n".getBytes(StandardCharsets.UTF_8));

    private static final List<ResourceDefinition> RESOURCES = List.of(
            new ResourceDefinition("reports/2026-q3.parquet", "2026 Q3 report",
                    "application/vnd.apache.parquet",
                    "Quarterly revenue extract. Binary, so it is served as a blob."),
            new ResourceDefinition("raw/events-0001.jsonl", "Raw event batch 0001",
                    "application/x-ndjson",
                    "One JSON object per line. Text, so it is served inline."));

    @Override
    public List<ResourceDefinition> resources() {
        return RESOURCES;
    }

    @Override
    public List<ResourceTemplate> resourceTemplates() {
        // {+key} is a reserved expansion, so it matches keys that contain slashes.
        return List.of(ResourceTemplate.of("{+key}", "any object by key", null));
    }

    @Override
    public Optional<ResourceContent> readResource(String uri) {
        byte[] bytes = CONTENTS.get(uri);
        if (bytes == null) {
            return Optional.empty();
        }
        ResourceDefinition definition = RESOURCES.stream()
                .filter(r -> r.uri().equals(uri))
                .findFirst()
                .orElseThrow();
        // This adapter knows which of its objects are text; that knowledge does not exist in the
        // mime type of an arbitrary object store, which is why the decision lives here.
        if ("raw/events-0001.jsonl".equals(uri)) {
            return Optional.of(ResourceContent.text(uri, definition.mimeType(),
                    new String(bytes, StandardCharsets.UTF_8)));
        }
        return Optional.of(ResourceContent.binary(uri, definition.mimeType(), bytes));
    }

    @Override
    public String namespace() {
        return "storage";
    }

    @Override
    public String description() {
        return "Object storage buckets (demo data)";
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                ToolDefinition.of("list_objects",
                        "Lists objects in the bucket.",
                        Json.obj().put("type", "object")),
                ToolDefinition.builder("delete_object")
                        .description("Permanently deletes one object. Requires explicit confirmation.")
                        .inputSchema(Json.obj()
                                .put("type", "object")
                                .set("properties", Json.obj().set("key", Json.obj()
                                        .put("type", "string")
                                        .put("description", "Object key to delete")))
                                .set("required", Json.arr().add("key")))
                        .mrtr(true)
                        .build());
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        return switch (toolName) {
            case "list_objects" -> listObjects();
            case "delete_object" -> deleteObject(context);
            default -> ToolResult.error("storage has no tool named '" + toolName + "'");
        };
    }

    private ToolResult listObjects() {
        ArrayNode objects = Json.arr();
        OBJECTS.forEach(objects::add);
        return ToolResult.of(
                OBJECTS.size() + " object(s) in the bucket",
                Json.obj().set("objects", objects).put("count", OBJECTS.size()));
    }

    private ToolResult deleteObject(RequestContext context) {
        if (!context.isResumption()) {
            String key = context.stringArg("key");
            if (key == null || key.isBlank()) {
                return ToolResult.error("'key' is required");
            }
            return ToolResult.inputRequired(
                    Map.of("confirm_delete", InputRequest.elicitation(Json.obj()
                            .put("message", "Delete object '" + key + "'? This cannot be undone.")
                            .set("requestedSchema", Json.obj()
                                    .put("type", "object")
                                    .set("properties", Json.obj().set("confirm", Json.obj()
                                            .put("type", "boolean")
                                            .put("description", "Set true to delete")))
                                    .set("required", Json.arr().add("confirm"))))),
                    Json.obj().put("key", key));
        }

        JsonNode state = context.continuationState();
        String key = state == null ? context.stringArg("key") : state.path("key").asString();

        InputResponse answer = context.input("confirm_delete");
        if (answer == null || !answer.isAccept()) {
            return ToolResult.error("Delete cancelled: no confirmation received for '" + key + "'");
        }
        return ToolResult.of("Deleted '" + key + "'",
                Json.obj().put("deleted", key).put("status", "deleted"));
    }
}