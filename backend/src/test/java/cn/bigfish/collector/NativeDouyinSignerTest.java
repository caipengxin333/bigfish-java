package cn.bigfish.collector;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeDouyinSignerTest {
    // Public compatibility vectors from the MIT source pinned in NativeWebcastSignature. No captured user data.
    private static final byte[] STUB = HexFormat.of().parseHex("704f436b2558b0d7a1c7e758527dd8f1");

    @Test void matchesBothPublishedNativeVectorsIncludingZeroSequence() {
        assertEquals("6pt0VC0aRZDP7n07", NativeWebcastSignature.withValues(STUB, 1, true, 63, 63));
        assertEquals("fD+evFuzasjOC8Uu", NativeWebcastSignature.withValues(STUB, 0, false, 0, 6));
        assertThrows(IllegalArgumentException.class, () -> NativeWebcastSignature.withValues(new byte[15], 1, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeWebcastSignature.withValues(STUB, 64, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeWebcastSignature.withValues(STUB, 1, true, 255, 0));
    }

    @Test void sequenceWrapsAt64AndRandomBoundsMatchSource() throws Exception {
        var generator = new NativeWebcastSignature(new SecureRandom() {
            private int calls;
            @Override public int nextInt(int bound) {
                assertEquals(calls++ % 3 == 0 ? 256 : 255, bound); return 63;
            }
        });
        for (int count = 1; count <= 65; count++) {
            assertEquals(NativeWebcastSignature.withValues(STUB, count & 63, true, 63, 63), generator.sign(STUB));
        }
    }

    @Test void orderedStubFreshTargetsAndEscapedSignatureMatchActualQuery() throws Exception {
        var clock = new MutableClock();
        var signer = new NativeDouyinSigner(clock, new NativeWebcastSignature(new SecureRandom() {
            private int calls;
            @Override public int nextInt(int bound) { return calls++ % 3 == 2 ? 6 : 0; }
        }));
        var first = signer.sign(request(clock, "nonce-one"));
        Map<String, String> query = query(first);
        assertEquals("23c5319700f2c06e476908de1233f50f", HexFormat.of().formatHex(NativeDouyinSigner.signingStub(query)));
        assertEquals("123", query.get("room_id")); assertEquals("456", query.get("user_unique_id"));
        assertEquals("5.0 SyntheticFixture/1", query.get("browser_version"));
        assertEquals("", query.get("cursor")); assertFalse(query.get("internal_ext").contains("wrds_v"));
        assertTrue(query.get("internal_ext").contains("fetch_time:" + clock.instant().toEpochMilli()));
        assertEquals("wss", first.endpoint().getScheme()); assertEquals("nonce-one", first.nonce());
        assertEquals(clock.instant().plusSeconds(30), first.expiresAt());
        assertEquals(NativeWebcastSignature.withValues(NativeDouyinSigner.signingStub(query), 1, false, 0, 6), query.get("signature"));
        assertTrue(query.get("signature").contains("+"));
        assertTrue(first.endpoint().getRawQuery().contains("%2B")); // '+' must not be decoded as a space.
        clock.now = clock.now.plusSeconds(1);
        var second = signer.sign(request(clock, "nonce-two"));
        assertNotEquals(first.endpoint(), second.endpoint());
        assertNotEquals(query.get("internal_ext"), query(second).get("internal_ext"));
        assertEquals(clock.instant(), second.issuedAt());
        assertFalse(first.toString().contains("signature=")); assertFalse(request(clock, "nonce").toString().contains("456"));
        assertTrue(signer.provenance().isDeclared()); assertEquals("MIT", signer.provenance().license());
    }

    @Test void rejectsStaleInvalidAndInterruptedRequestsWithoutDetails() {
        var clock = new MutableClock(); var signer = new NativeDouyinSigner(clock, new NativeWebcastSignature());
        var old = request(clock, "old"); clock.now = clock.now.plusSeconds(11);
        assertEquals(SIGNATURE_EXPIRED, assertThrows(DouyinFailure.class, () -> signer.sign(old)).code());
        var invalid = new DouyinSigner.Request("invalid-room", "456", "synthetic\r\nheader", "nonce", clock.instant());
        var failure = assertThrows(DouyinFailure.class, () -> signer.sign(invalid));
        assertEquals(SIGNATURE_INVALID, failure.code()); assertNull(failure.getCause());
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> signer.sign(request(clock, "cancel"))); }
        finally { Thread.interrupted(); }
    }

    @Test void productionSignerWorksThroughProviderAndReleasesSigningWorker() throws Exception {
        var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, new NativeDouyinSigner(),
                DouyinRoomResolverTest.resolver(DouyinRoomResolverTest.fixture("live")), Clock.systemUTC());
        var first = provider.resolve("123456"); var second = provider.resolve("123456", first.internalRoomId);
        assertNotEquals(first.endpoint, second.endpoint); assertEquals(2, provider.signingAttempts());
        assertEquals(0, provider.signingWorkers()); assertEquals(0, provider.inFlightRequests());
    }

    private static DouyinSigner.Request request(Clock clock, String nonce) {
        return new DouyinSigner.Request("123", "456", "Mozilla/5.0 SyntheticFixture/1", nonce, clock.instant());
    }
    static Map<String, String> query(DouyinSigner.SignedTarget signed) {
        var values = new HashMap<String, String>();
        for (String pair : signed.endpoint().getRawQuery().split("&")) {
            String[] entry = pair.split("=", 2);
            values.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8), URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return values;
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
