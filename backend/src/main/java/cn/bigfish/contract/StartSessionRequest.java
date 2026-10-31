package cn.bigfish.contract;
import jakarta.validation.constraints.NotNull;

/** Optional speechMode preserves v1 clients; explicit null is still rejected by validation. */
public final class StartSessionRequest {
    public enum Mode { MOCK, DOUYIN }
    public enum ConversationMode { MOCK, DEEPSEEK }
    public enum SpeechMode { LOCAL_TEST_TONE, ALIYUN_NLS }
    @NotNull public Mode mode;
    @NotNull public ConversationMode conversationMode;
    @NotNull public SpeechMode speechMode = SpeechMode.LOCAL_TEST_TONE;
    public StartSessionRequest() {}
    public StartSessionRequest(Mode mode, ConversationMode conversationMode) { this.mode=mode; this.conversationMode=conversationMode; }
    public Mode mode() { return mode; }
    public ConversationMode conversationMode() { return conversationMode; }
    public SpeechMode speechMode() { return speechMode; }
}
