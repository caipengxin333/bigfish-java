package cn.bigfish.contract;

import jakarta.validation.constraints.NotNull;

/** Both choices are explicit; no implicit paid calls or fallback to MOCK. */
public record StartSessionRequest(@NotNull Mode mode, @NotNull ConversationMode conversationMode) {
    public enum Mode { MOCK, DOUYIN }
    public enum ConversationMode { MOCK, DEEPSEEK }
}
