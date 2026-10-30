package io.github.kurukurumi33.mcp.spi;

import io.github.kurukurumi33.mcp.protocol.Json;
import tools.jackson.databind.JsonNode;

/**
 * One tool a backend exposes, described in the same shape the protocol sends to clients.
 *
 * <p>{@code name} is deliberately <em>unqualified</em>. The registry prefixes it with the owning
 * adapter's {@link BackendAdapter#namespace()} so two backends can both publish a {@code list} tool
 * without colliding on the wire.
 *
 * @param name           tool name within its namespace, e.g. {@code list_files}
 * @param description    shown to the model; this is what the agent reasons over when choosing a tool
 * @param inputSchema    JSON Schema for the arguments object; never null
 * @param outputSchema   JSON Schema for {@code structuredContent}, or null when the tool returns only content blocks
 * @param mrtr           true when the tool may return {@code input_required} and be resumed with {@code inputResponses}
 * @param streaming      true when the tool emits progress notifications on the request's own response stream
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        boolean mrtr,
        boolean streaming) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (inputSchema == null) {
            inputSchema = Json.obj().put("type", "object");
        }
    }

    /** A tool that takes an object, returns text, needs no follow-up round and emits no progress. */
    public static ToolDefinition of(String name, String description, JsonNode inputSchema) {
        return new ToolDefinition(name, description, inputSchema, null, false, false);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private JsonNode inputSchema = Json.obj().put("type", "object");
        private JsonNode outputSchema;
        private boolean mrtr;
        private boolean streaming;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchema(JsonNode inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder outputSchema(JsonNode outputSchema) {
            this.outputSchema = outputSchema;
            return this;
        }

        public Builder mrtr(boolean mrtr) {
            this.mrtr = mrtr;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(name, description, inputSchema, outputSchema, mrtr, streaming);
        }
    }
}