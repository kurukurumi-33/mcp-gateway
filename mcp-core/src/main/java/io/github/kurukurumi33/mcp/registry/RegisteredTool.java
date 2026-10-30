package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;

/**
 * A tool together with the adapter that owns it.
 *
 * <p>This pairing is the whole point of the registry: the client sees one flat list of qualified
 * names, while the gateway keeps the backend that must handle each call.
 */
public record RegisteredTool(BackendAdapter adapter, ToolDefinition definition) {

    /** The name clients see, e.g. {@code storage_list_files}. */
    public String qualifiedName() {
        return adapter.prefixedToolNames()
                ? adapter.namespace() + "_" + definition.name()
                : definition.name();
    }

    /** False when the backend is down; the tool stays listed but calls fail fast. */
    public boolean callable() {
        return adapter.health().isCallable();
    }
}