package cn.bigfish.contract;
import java.util.function.Consumer;

/** Implementations must never call a model/TTS or block their WebSocket callback on business processing. */
public interface CollectorPort extends AutoCloseable {
    void start(String roomId, String sessionId, Consumer<LiveEvent> sink);
    void stop();
    State state();
    @Override default void close() { stop(); }
    enum State { STOPPED, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }
}
