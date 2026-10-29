package cn.bigfish.conversation;

import cn.bigfish.contract.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Deterministic, no network or credentials. */
public final class MockConversation implements ConversationPort {
    @Override public CompletionStage<Void> reply(String id, LiveEvent event, ConfigSnapshot config,
                                                 Duration deadline, Consumer<String> sink) {
        if (deadline.isNegative() || deadline.isZero()) return CompletableFuture.failedFuture(new TimeoutException());
        sink.accept("[模拟回复] " + event.payload().get("content") + "，谢谢你的留言！");
        return CompletableFuture.completedFuture(null);
    }
    @Override public void cancel(String id) {}
}
