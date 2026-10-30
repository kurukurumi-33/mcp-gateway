package io.github.kurukurumi33.mcp.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single shared Jackson 3 mapper plus the few node helpers the protocol code needs.
 *
 * <p>Jackson 3 exceptions are unchecked, so the only wrapping here is to turn a malformed
 * body into the protocol's own {@link McpProtocol#PARSE_ERROR}.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parses a body, translating any Jackson failure into a {@code -32700} protocol error. */
    public static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JacksonException e) {
            throw new McpException(McpProtocol.PARSE_ERROR, 400, "Malformed JSON: " + e.getOriginalMessage());
        }
    }

    public static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    public static ObjectNode obj() {
        return NODES.objectNode();
    }

    public static ArrayNode arr() {
        return NODES.arrayNode();
    }

    /** Null-safe accessor: returns the node at {@code field}, or {@code null} when absent or JSON null. */
    public static JsonNode orNull(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode child = parent.get(field);
        return (child == null || child.isNull()) ? null : child;
    }
}