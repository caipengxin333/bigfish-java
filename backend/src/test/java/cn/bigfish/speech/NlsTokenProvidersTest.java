package cn.bigfish.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.AlibabaNlsTokenClientTest.failure;
import static cn.bigfish.speech.SpeechFailure.Code.*;

class NlsTokenProvidersTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void staticIsDefaultAndDoesNotReadOrFallBackToRamCredentials() {
        Set<String> reads = new HashSet<>();
        try (var handle = NlsTokenProviders.create(true, key -> {
            reads.add(key);
            return key.equals("BIGFISH_ALIYUN_NLS_TOKEN") ? "fake-static-token" : null;
        }, mapper)) {
            assertThat(handle.snapshot().mode()).isEqualTo(NlsTokenProviders.Mode.STATIC);
            var token = handle.acquire(Duration.ofSeconds(1)).toCompletableFuture().join();
            handle.rejected(token); failure(handle.acquire(Duration.ofSeconds(1)), TOKEN_REJECTED);
            assertThat(reads).containsExactlyInAnyOrder("BIGFISH_ALIYUN_NLS_TOKEN_MODE", "BIGFISH_ALIYUN_NLS_TOKEN");
            assertThat(handle.snapshot().toString()).doesNotContain("fake-static-token");
            assertThat(handle.snapshot().expiresAt()).isNull();
        }
    }
    @Test void ramConstructionAndSnapshotAreLazyAndUseOnlyDedicatedCredentials() {
        Set<String> reads = new HashSet<>();
        try (var handle = NlsTokenProviders.create(true, key -> {
            reads.add(key);
            return switch(key) {
                case "BIGFISH_ALIYUN_NLS_TOKEN_MODE" -> "RAM";
                case "BIGFISH_ALIYUN_ACCESS_KEY_ID" -> "fake-ram-id";
                case "BIGFISH_ALIYUN_ACCESS_KEY_SECRET" -> "fake-ram-secret";
                default -> throw new AssertionError("Unexpected credential source");
            };
        }, mapper)) {
            for (int i=0;i<100;i++) { handle.status(); handle.snapshot(); }
            assertThat(handle.snapshot().attempts()).isZero();
            assertThat(handle.snapshot().mode()).isEqualTo(NlsTokenProviders.Mode.RAM);
            assertThat(handle.snapshot().refreshResult()).isEqualTo(RefreshingNlsTokenProvider.RefreshResult.NOT_REQUESTED);
            assertThat(handle.toString()).doesNotContain("fake-ram-id", "fake-ram-secret");
            assertThat(reads).hasSize(3);
        }
    }
    @Test void disabledDoesNotReadAnythingAndInvalidOrMissingRamNeverFallsBack() {
        assertThatThrownBy(() -> NlsTokenProviders.create(false, key -> { throw new AssertionError(); }, mapper)).hasMessage("NLS_DISABLED");
        assertThatThrownBy(() -> NlsTokenProviders.create(true, key -> key.equals("BIGFISH_ALIYUN_NLS_TOKEN_MODE") ? "AUTO" : "fake", mapper))
            .hasMessage("NLS_TOKEN_MODE_INVALID");
        assertThatThrownBy(() -> NlsTokenProviders.create(true, key -> switch(key) {
            case "BIGFISH_ALIYUN_NLS_TOKEN_MODE" -> "RAM";
            case "BIGFISH_ALIYUN_NLS_TOKEN" -> throw new AssertionError("Must not fall back");
            default -> null;
        }, mapper)).hasMessage("NLS_MISSING_CREDENTIALS");
    }
}
