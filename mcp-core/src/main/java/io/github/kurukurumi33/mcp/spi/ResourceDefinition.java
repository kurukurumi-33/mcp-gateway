package io.github.kurukurumi33.mcp.spi;

/**
 * One readable resource a backend exposes.
 *
 * <p>{@code uri} is deliberately <em>not</em> a full URI. It is a path owned by the backend, and the
 * registry publishes it as {@code mcp://<namespace>/<uri>}. The gateway owns the scheme for the same
 * reason it owns tool-name prefixes: two backends may both call a resource {@code config.json}, and a
 * flat catalog would route the second one onto the first.
 *
 * @param uri         backend-local path, no scheme, e.g. {@code objects/report.csv}
 * @param name        human-readable name; defaults to the uri
 * @param mimeType    media type of the content, or null when unknown
 * @param description what it holds; shown to the model
 */
public record ResourceDefinition(String uri, String name, String mimeType, String description) {

    public ResourceDefinition {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("resource uri must not be blank");
        }
        if (uri.contains("://") || uri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("resource uri must be backend-local, without a scheme: '" + uri
                    + "'. The gateway adds mcp://<namespace>/ itself.");
        }
        if (uri.startsWith("/")) {
            throw new IllegalArgumentException("resource uri must not start with '/': '" + uri
                    + "'. The namespace is already a path segment.");
        }
        if (name == null || name.isBlank()) {
            name = uri;
        }
    }

    public static ResourceDefinition of(String uri, String name) {
        return new ResourceDefinition(uri, name, null, null);
    }

    public static ResourceDefinition of(String uri, String name, String mimeType) {
        return new ResourceDefinition(uri, name, mimeType, null);
    }
}