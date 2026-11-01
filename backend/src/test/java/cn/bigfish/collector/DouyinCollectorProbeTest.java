package cn.bigfish.collector;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class DouyinCollectorProbeTest {
    @Test void probeIsExplicitlyOptInAndDoesNotLoadSignerOrConnectWhenDisabled() {
        assertEquals(PROBE_DISABLED, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.run(key -> null)).code());
        assertEquals(ROOM_REQUIRED, assertThrows(DouyinFailure.class, () ->
                DouyinCollectorProbe.run(Map.of("BIGFISH_DOUYIN_LIVE_PROBE", "true")::get)).code());
    }
    @Test void shortBoundedProbeRequiresAnExplicitActionAndReviewedSigner() throws Exception {
        var valid = Map.of("BIGFISH_DOUYIN_LIVE_PROBE", "true", "BIGFISH_DOUYIN_ROOM", "123456",
                "BIGFISH_DOUYIN_PROBE_ACTION", "receive");
        assertEquals(60, DouyinCollectorProbe.configuration(valid::get).seconds());
        assertEquals(3, DouyinCollectorProbe.configuration(valid::get).maxAttempts());
        var single = new java.util.HashMap<>(valid); single.put("BIGFISH_DOUYIN_PROBE_MAX_ATTEMPTS", "1");
        assertEquals(1, DouyinCollectorProbe.configuration(single::get).maxAttempts());
        for (String invalid : new String[]{"0", "4", "not-a-number"}) {
            single.put("BIGFISH_DOUYIN_PROBE_MAX_ATTEMPTS", invalid);
            assertEquals(PROBE_CONFIGURATION_INVALID, assertThrows(DouyinFailure.class,
                    () -> DouyinCollectorProbe.configuration(single::get)).code());
        }
        assertEquals("guest", DouyinCollectorProbe.configuration(valid::get).credentialMode());
        assertEquals(SIGNER_UNAVAILABLE, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.run(valid::get)).code());
        var tooLong = new java.util.HashMap<>(valid); tooLong.put("BIGFISH_DOUYIN_PROBE_SECONDS", "121");
        assertEquals(PROBE_CONFIGURATION_INVALID, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.configuration(tooLong::get)).code());
        tooLong.remove("BIGFISH_DOUYIN_PROBE_SECONDS"); tooLong.remove("BIGFISH_DOUYIN_PROBE_ACTION");
        assertEquals(PROBE_CONFIGURATION_INVALID, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.configuration(tooLong::get)).code());
    }
    @Test void environmentReadsAreRestrictedToDocumentedCredentialKeysAndErrorsAreFixed() {
        var allowed = java.util.Set.of("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT", "BIGFISH_DOUYIN_COOKIE",
                "BIGFISH_DOUYIN_USER_AGENT", "BIGFISH_DOUYIN_USER_UNIQUE_ID");
        assertEquals(CREDENTIALS_MISSING, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.credentials(key -> {
            assertTrue(allowed.contains(key)); return null;
        })).code());
        assertEquals(CREDENTIALS_INVALID, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.credentials(key ->
                key.equals("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT") ? "synthetic-invalid-date" : null)).code());
    }

    @Test void guestModeDoesNotReadExternalCredentialsAndExternalModeMustBeExplicit() throws Exception {
        var env = new java.util.HashMap<>(Map.of("BIGFISH_DOUYIN_LIVE_PROBE", "true", "BIGFISH_DOUYIN_ROOM", "123456",
                "BIGFISH_DOUYIN_PROBE_ACTION", "receive"));
        var config = DouyinCollectorProbe.configuration(env::get);
        var provider = DouyinCollectorProbe.provider(config, key -> { fail("Guest mode must not read credential environment"); return null; },
                new NativeDouyinSigner(), DouyinRoomResolverTest.resolver("synthetic"));
        assertEquals(0, provider.requestsStarted());
        env.put("BIGFISH_DOUYIN_CREDENTIAL_MODE", "external");
        var external = DouyinCollectorProbe.configuration(env::get);
        assertEquals(CREDENTIALS_MISSING, assertThrows(DouyinFailure.class,
                () -> DouyinCollectorProbe.provider(external, env::get, new NativeDouyinSigner(), DouyinRoomResolverTest.resolver("synthetic"))).code());
        env.put("BIGFISH_DOUYIN_CREDENTIAL_MODE", "unknown");
        assertEquals(PROBE_CONFIGURATION_INVALID, assertThrows(DouyinFailure.class, () -> DouyinCollectorProbe.configuration(env::get)).code());
    }
}
