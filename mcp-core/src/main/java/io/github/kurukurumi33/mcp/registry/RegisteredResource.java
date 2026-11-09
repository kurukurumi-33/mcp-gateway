package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;

/**
 * A resource together with the adapter that serves it.
 *
 * <p>Same pairing as {@link RegisteredTool}: the client sees one flat list of {@code mcp://} uris,
 * while the gateway keeps which backend owns each one.
 */
public record RegisteredResource(BackendAdapter adapter, ResourceDefinition definition) {

    /** The uri clients see, e.g. {@code mcp://storage/objects/report.csv}. */
    public String qualifiedUri() {
        return ResourceUris.qualify(adapter.namespace(), definition.uri());
    }

    /** False when the backend is down; the resource stays listed so the client can see why. */
    public boolean available() {
        return adapter.health().isCallable();
    }
}