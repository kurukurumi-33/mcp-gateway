package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signs and verifies {@code requestState}, the blob that carries a multi-round call between HTTP requests.
 *
 * <p>Removing sessions left exactly one place for the server to remember where a conversation was:
 * a state token the client stores and replays. The protocol is explicit that the client treats it as
 * opaque, but nothing stops a client — or anything sitting in front of one — from editing it. Since
 * the gateway acts on whatever comes back, an unsigned token would let a caller rewrite its own
 * pending operation: change which record is about to be deleted, or which tenant a query runs as.
 *
 * <p>So the token is {@code base64url(payload) "." base64url(HMAC-SHA256(payload))}. Payload and
 * signature are checked together, and a mismatch is a hard protocol error rather than a fallback,
 * because a state token that does not verify is not stale — it is forged.
 */
public final class RequestStateCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public RequestStateCodec(byte[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("requestState secret must be at least 16 bytes");
        }
        this.secret = secret.clone();
    }

    /** Short-lived key for tests and local demos. Production must supply a stable key, or restarts invalidate tokens. */
    public static RequestStateCodec random() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new RequestStateCodec(key);
    }

    public String encode(JsonNode state) {
        byte[] payload = Json.write(state).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    /**
     * Verifies and unwraps a token.
     *
     * @throws McpException with {@code -32602} when the token is malformed or its signature does not match
     */
    public JsonNode decode(String token) {
        if (token == null || token.isBlank()) {
            throw McpException.invalidParams("requestState is required to resume a multi-round call");
        }
        int separator = token.indexOf('.');
        if (separator < 1 || separator == token.length() - 1) {
            throw McpException.invalidParams("requestState is not a well-formed signed token");
        }

        byte[] payload;
        byte[] presented;
        try {
            payload = DECODER.decode(token.substring(0, separator));
            presented = DECODER.decode(token.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams("requestState is not valid base64url");
        }

        if (!MessageDigest.isEqual(sign(payload), presented)) {
            throw McpException.invalidParams("requestState failed signature verification");
        }
        return Json.parse(new String(payload, StandardCharsets.UTF_8));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable in this JVM", e);
        }
    }
}