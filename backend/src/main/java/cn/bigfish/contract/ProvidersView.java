package cn.bigfish.contract;
import java.util.List;
import java.time.Instant;

/** Only presence and verification state; no credential values or provider exception text. */
public record ProvidersView(Provider deepseek, Provider aliyun, Provider douyin,
                            List<String> aliyunVoices, int speechTextLimit, int audioDurationLimitSeconds,
                            TokenState aliyunToken) {
    public record Provider(boolean configured, boolean enabled, String state, String reason) {}
    public record TokenState(String mode, String status, Instant expiresAt, Long remainingSeconds,
                             String refreshResult, String lastError, Instant lastAttemptAt,
                             Instant lastSuccessAt, long retryAfterMillis, long attempts) {}
    public record ModelCheck(String authentication, String selectedModel, String generation,
                             List<String> availableModels, String code, long elapsedMs) {}
}
