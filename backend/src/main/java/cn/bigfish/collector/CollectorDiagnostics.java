package cn.bigfish.collector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Only fixed error codes and counts; never retains exception messages, frames, URLs or credentials. */
public final class CollectorDiagnostics {
    public enum Counter { RECEIVED, EMITTED, DUPLICATE, MALFORMED, UNKNOWN, QUEUE_FULL, EXPIRED,
        SINK_FAILURE, CONNECT_FAILURE, RECONNECT, TRANSPORT_FAILURE, HEARTBEAT, ACK, ENDED, CONNECTED,
        HANDSHAKE_ATTEMPT, ROOM_CHANGED }

    private final Map<Counter, LongAdder> counters = new ConcurrentHashMap<>();
    private final InboundDiagnostics inbound = new InboundDiagnostics();
    InboundDiagnostics inbound() { return inbound; }
    private volatile String lastError = "";
    private volatile long lastErrorElapsedMillis;

    void increment(Counter counter) { counters.computeIfAbsent(counter, key -> new LongAdder()).increment(); }
    void error(Counter counter, long elapsedMillis) {
        increment(counter);
        lastError = counter.name();
        lastErrorElapsedMillis = Math.max(0, elapsedMillis);
    }

    void error(DouyinFailure.Code code, long elapsedMillis) {
        increment(Counter.CONNECT_FAILURE);
        lastError = code.name();
        lastErrorElapsedMillis = Math.max(0, elapsedMillis);
    }

    public long count(Counter counter) {
        var value = counters.get(counter);
        return value == null ? 0 : value.sum();
    }

    public String lastError() { return lastError; }
    public long lastErrorElapsedMillis() { return lastErrorElapsedMillis; }
}
