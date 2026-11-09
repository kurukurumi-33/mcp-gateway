package io.github.kurukurumi33.mcp.spi;

/**
 * The bytes of one resource, either text or binary but never both.
 *
 * <p>The protocol has two content shapes, {@code TextResourceContents} and
 * {@code BlobResourceContents}, and the choice is not cosmetic: a client renders text directly and
 * base64-decodes a blob into a file. An adapter therefore has to decide, at read time, whether what
 * it fetched is text. Guessing from the mime type inside the engine would be wrong for anything
 * served as {@code application/octet-stream}.
 *
 * @param uri      the backend-local uri that was read, echoed back for correlation
 * @param mimeType media type, or null when unknown
 * @param text     text content, or null for a binary resource
 * @param blob     raw bytes, or null for a text resource
 */
public record ResourceContent(String uri, String mimeType, String text, byte[] blob) {

    public ResourceContent {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
        boolean hasText = text != null;
        boolean hasBlob = blob != null;
        if (hasText == hasBlob) {
            throw new IllegalArgumentException("exactly one of text or blob must be set for '" + uri + "'");
        }
        if (hasBlob) {
            // A record's array component would otherwise be shared with the caller, so a mutation
            // after the fact would change the content a client already received.
            blob = blob.clone();
        }
    }

    public static ResourceContent text(String uri, String mimeType, String text) {
        return new ResourceContent(uri, mimeType, text, null);
    }

    public static ResourceContent binary(String uri, String mimeType, byte[] blob) {
        return new ResourceContent(uri, mimeType, null, blob);
    }

    public boolean isBinary() {
        return blob != null;
    }
}