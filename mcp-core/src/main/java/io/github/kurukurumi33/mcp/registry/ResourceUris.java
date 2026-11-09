package io.github.kurukurumi33.mcp.registry;

import java.util.Optional;

/**
 * The {@code mcp://} scheme the gateway publishes resources under.
 *
 * <p>Adapters never see these uris. A backend declares {@code objects/report.csv}, the gateway
 * publishes {@code mcp://storage/objects/report.csv}, and strips the prefix again before handing the
 * read back. That indirection is what stops two backends from colliding on a path, and it is why the
 * gateway can route a read without knowing anything about the backend's own addressing.
 */
public final class ResourceUris {

    /** URI scheme owned by the gateway. */
    public static final String SCHEME = "mcp";

    private ResourceUris() {}

    public static String qualify(String namespace, String localUri) {
        return SCHEME + "://" + namespace + "/" + localUri;
    }

    /**
     * Splits {@code mcp://<namespace>/<rest>}.
     *
     * <p>Empty for anything else, including a uri the client invented with a different scheme. The
     * caller turns that into "no such resource" rather than trying to guess what the client meant.
     */
    public static Optional<Parsed> parse(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String prefix = SCHEME + "://";
        if (!uri.startsWith(prefix)) {
            return Optional.empty();
        }
        int slash = uri.indexOf('/', prefix.length());
        if (slash < 0) {
            return Optional.empty();
        }
        String namespace = uri.substring(prefix.length(), slash);
        String local = uri.substring(slash + 1);
        if (namespace.isEmpty() || local.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(namespace, local));
    }

    /** A namespace plus the backend-local uri that follows it. */
    public record Parsed(String namespace, String uri) {}
}