package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * <p>So the token is {@code base64url(envelope) "." base64url(HMAC-SHA256(envelope))}, where the
 * envelope is {@code {"exp": <epoch seconds>, "state": <handler state>}}. A signature mismatch is a
 * hard protocol error rather than a fallback, because a state token that does not verify is not
 * stale — it is forged.
 *
 * <p>The expiry bounds a different problem: a correctly signed token is valid forever unless the
 * server remembers which tokens it has already seen, and remembering is exactly the shared state
 * the protocol set out to remove. A short lifetime keeps a captured token reusable for at most that
 * window. True single-use tokens would need a nonce store (Redis or a table), which is a deliberate
 * trade: replay is bounded rather than eliminated.
 */
public final class RequestStateCodec {

    /** Long enough for a human to answer a confirmation prompt, short enough to bound replay. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public RequestStateCodec(byte[] secret) {
        this(secret, DEFAULT_TTL, Clock.systemUTC());
    }

    public RequestStateCodec(byte[] secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    /**
     * @param ttl how long an issued token stays valid; must be positive
     * @param clock source of "now", injectable so expiry is testable without sleeping
     */
    public RequestStateCodec(byte[] secret, Duration ttl, Clock clock) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("requestState secret must be at least 16 bytes");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("requestState ttl must be positive");
        }
        this.secret = secret.clone();
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Short-lived key for tests and local demos. Production must supply a stable key, or restarts invalidate tokens. */
    public static RequestStateCodec random() {
        return random(DEFAULT_TTL);
    }

    public static RequestStateCodec random(Duration ttl) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new RequestStateCodec(key, ttl);
    }

    public String encode(JsonNode state) {
        JsonNode envelope = Json.obj()
                .put("exp", clock.instant().plus(ttl).getEpochSecond())
                .set("state", state == null ? Json.obj() : state);
        byte[] payload = Json.write(envelope).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    /**
     * Verifies and unwraps a token.
     *
     * <p>The signature is checked before the expiry is read, so an attacker cannot probe the
     * envelope by watching which error comes back.
     *
     * @throws McpException with {@code -32602} when the token is malformed, forged or expired
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

        JsonNode envelope = Json.parse(new String(payload, StandardCharsets.UTF_8));
        JsonNode exp = envelope.path("exp");
        if (!exp.isIntegralNumber()) {
            throw McpException.invalidParams("requestState was signed without an expiry");
        }
        if (clock.instant().getEpochSecond() >= exp.asLong()) {
            throw McpException.invalidParams("requestState expired at "
                    + Instant.ofEpochSecond(exp.asLong()) + "; start the call again");
        }
        JsonNode state = envelope.path("state");
        if (state.isMissingNode()) {
            throw McpException.invalidParams("requestState carries no state");
        }
        return state;
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