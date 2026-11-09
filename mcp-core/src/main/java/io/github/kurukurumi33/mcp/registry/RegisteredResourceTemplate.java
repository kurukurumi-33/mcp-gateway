package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.ResourceTemplate;

/**
 * A resource template together with the adapter that serves it.
 *
 * <p>Kept as a pair rather than as a rewritten {@link ResourceTemplate} because the template itself
 * refuses a scheme: adapters may only declare backend-local paths, and the qualified form is derived
 * here instead of being stored back into the value the adapter handed in.
 */
public record RegisteredResourceTemplate(BackendAdapter adapter, ResourceTemplate template) {

    /** The template clients see, e.g. {@code mcp://store/objects/{key}}. */
    public String qualifiedUriTemplate() {
        return ResourceUris.qualify(adapter.namespace(), template.uriTemplate());
    }
}