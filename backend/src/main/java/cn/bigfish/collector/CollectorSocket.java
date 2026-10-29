package cn.bigfish.collector;

import java.time.Duration;
import java.time.Instant;

/** Transport seam used for deterministic disconnection/timeout tests. */
interface CollectorSocket extends AutoCloseable {
    record Frame(byte[] bytes, Instant receivedAt) {
        @Override public String toString() { return "Frame[redacted]"; }
    }
    void open(DouyinConnection connection) throws Exception;
    Frame poll(Duration wait) throws InterruptedException;
    boolean isOpen();
    long lastActivityNanos();
    void acknowledge(byte[] frame) throws Exception;
    void heartbeat(byte[] frame, DouyinConnection.Heartbeat kind) throws Exception;
    @Override void close();
}
