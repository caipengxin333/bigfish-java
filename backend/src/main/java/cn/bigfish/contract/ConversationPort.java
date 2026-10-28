package cn.bigfish.contract;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
public interface ConversationPort {
    CompletionStage<Void> reply(String requestId, LiveEvent event, ConfigSnapshot config,
                               Duration deadline, Consumer<String> sentenceSink);
    void cancel(String requestId);
}
