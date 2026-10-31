package cn.bigfish.contract;
import java.util.List;

/** Only presence and verification state; no credential values or provider exception text. */
public record ProvidersView(Provider deepseek, Provider aliyun, Provider douyin,
                            List<String> aliyunVoices, int speechTextLimit, int audioDurationLimitSeconds) {
    public record Provider(boolean configured, boolean enabled, String state, String reason) {}
    public record ModelCheck(String authentication, String selectedModel, String generation,
                             List<String> availableModels, String code, long elapsedMs) {}
}
