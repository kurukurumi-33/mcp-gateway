package io.github.kurukurumi33.mcp.spi;

/**
 * A family of resources rather than one: an RFC 6570 URI template a client fills in.
 *
 * <p>When a backend has ten thousand objects it cannot enumerate them as
 * {@link ResourceDefinition}s, but it can say "every {@code objects/{key}} is readable". The client
 * expands the template, gets a concrete uri, and reads it through the same path as any other
 * resource, so a template never needs its own read method.
 *
 * @param uriTemplate RFC 6570 template, backend-local and scheme-less, e.g. {@code objects/{key}}
 * @param name        human-readable name for the family
 * @param mimeType    media type shared by every resource matching the template, or null
 * @param description what the family holds; shown to the model
 */
public record ResourceTemplate(String uriTemplate, String name, String mimeType, String description) {

    public ResourceTemplate {
        if (uriTemplate == null || uriTemplate.isBlank()) {
            throw new IllegalArgumentException("uriTemplate must not be blank");
        }
        if (uriTemplate.contains("://") || uriTemplate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("uriTemplate must be backend-local, without a scheme: '"
                    + uriTemplate + "'");
        }
        if (name == null || name.isBlank()) {
            name = uriTemplate;
        }
    }

    public static ResourceTemplate of(String uriTemplate, String name, String mimeType) {
        return new ResourceTemplate(uriTemplate, name, mimeType, null);
    }
}