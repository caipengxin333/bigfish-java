package cn.bigfish.speech;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NlsRpcSignatureTest {
    @Test void matchesOfficialFixedGetVectorAndIndependentlyComputedPostVector() {
        // Public, expired, fictitious vector in Alibaba's OpenAPI documentation; not a live signed URL.
        var p = NlsRpcSignature.parameters("my_access_key_id", Instant.parse("2019-04-18T08:32:31Z"),
            "b924c8c3-6d03-4c5d-ad36-d984d3116788");
        assertThat(NlsRpcSignature.signature("GET", p, "my_access_key_secret")).isEqualTo("hHq4yNsPitlfDJ2L0nQPdugdEzM=");
        // Independently calculated with Python stdlib hmac/hashlib/urllib.parse, same fake inputs.
        assertThat(NlsRpcSignature.signature("POST", p, "my_access_key_secret")).isEqualTo("X4/yeE8FUchC5Wv7AZJybEuDWzw=");
    }
    @Test void percentEncodingSortingAndSignatureExclusionFollowRpcRules() {
        assertThat(NlsRpcSignature.encode("空 格+*/~")).isEqualTo("%E7%A9%BA%20%E6%A0%BC%2B%2A%2F~");
        assertThat(NlsRpcSignature.canonical(Map.of("z", "a b", "A", "+", "Signature", "excluded")))
            .isEqualTo("A=%2B&z=a%20b");
        var p = NlsRpcSignature.parameters("fixture", Instant.parse("2026-01-01T00:00:00.987Z"), "nonce");
        assertThat(p.get("Timestamp")).isEqualTo("2026-01-01T00:00:00Z");
        var changed = new HashMap<>(p); changed.put("SignatureNonce", "different");
        assertThat(NlsRpcSignature.signature("POST", p, "fixture-secret"))
            .isNotEqualTo(NlsRpcSignature.signature("POST", changed, "fixture-secret"));
    }
}
