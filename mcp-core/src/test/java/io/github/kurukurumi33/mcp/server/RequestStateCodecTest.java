package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestStateCodecTest {

    private static final byte[] SECRET =
            "unit-test-request-state-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant T0 = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    @DisplayName("a token round-trips the handler state")
    void roundTrip() {
        RequestStateCodec codec = new RequestStateCodec(SECRET, Duration.ofMinutes(5),
                new MutableClock(T0));

        JsonNode state = codec.decode(codec.encode(Json.obj().put("key", "a.parquet")));

        assertEquals("a.parquet", state.path("key").asString());
    }

    @Test
    @DisplayName("editing the payload breaks the signature")
    void rejectsEditedPayload() {
        RequestStateCodec codec = new RequestStateCodec(SECRET);
        String token = codec.encode(Json.obj().put("key", "a.parquet"));
        String signature = token.substring(token.indexOf('.') + 1);
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"exp\":9999999999,\"state\":{\"key\":\"b.parquet\"}}"
                        .getBytes(StandardCharsets.UTF_8))
                + "." + signature;

        McpException e = assertThrows(McpException.class, () -> codec.decode(forged));

        assertEquals(McpProtocol.INVALID_PARAMS, e.code());
        assertTrue(e.getMessage().contains("signature"), e.getMessage());
    }

    @Test
    @DisplayName("a token is still valid one second before its expiry")
    void acceptsJustBeforeExpiry() {
        MutableClock clock = new MutableClock(T0);
        RequestStateCodec codec = new RequestStateCodec(SECRET, Duration.ofSeconds(60), clock);

        String token = codec.encode(Json.obj().put("key", "a.parquet"));
        clock.advance(Duration.ofSeconds(59));

        assertEquals("a.parquet", codec.decode(token).path("key").asString());
    }

    @Test
    @DisplayName("a token is rejected once the expiry is reached")
    void rejectsExpiredToken() {
        MutableClock clock = new MutableClock(T0);
        RequestStateCodec codec = new RequestStateCodec(SECRET, Duration.ofSeconds(60), clock);

        String token = codec.encode(Json.obj().put("key", "a.parquet"));
        clock.advance(Duration.ofSeconds(60));

        McpException e = assertThrows(McpException.class, () -> codec.decode(token));

        assertEquals(McpProtocol.INVALID_PARAMS, e.code());
        assertTrue(e.getMessage().contains("expired"), e.getMessage());
    }

    @Test
    @DisplayName("a validly signed token without an expiry is refused, not treated as eternal")
    void rejectsTokenSignedWithoutExpiry() throws Exception {
        RequestStateCodec codec = new RequestStateCodec(SECRET);

        // The shape used before expiry was introduced: the bare handler state, correctly signed.
        String legacy = sign("{\"key\":\"a.parquet\"}");

        McpException e = assertThrows(McpException.class, () -> codec.decode(legacy));

        assertEquals(McpProtocol.INVALID_PARAMS, e.code());
        assertTrue(e.getMessage().contains("expiry"), e.getMessage());
    }

    @Test
    @DisplayName("an envelope without the state field is refused")
    void rejectsEnvelopeWithoutState() throws Exception {
        RequestStateCodec codec = new RequestStateCodec(SECRET);

        String token = sign("{\"exp\":9999999999}");

        McpException e = assertThrows(McpException.class, () -> codec.decode(token));

        assertEquals(McpProtocol.INVALID_PARAMS, e.code());
        assertTrue(e.getMessage().contains("no state"), e.getMessage());
    }

    @Test
    @DisplayName("a non-positive ttl is a configuration error, not an eternal token")
    void rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestStateCodec(SECRET, Duration.ZERO));
    }

    private static String sign(String payloadJson) throws Exception {
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(mac.doFinal(payload));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}