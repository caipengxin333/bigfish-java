package cn.bigfish.contract;
import java.time.Instant;
public record PlaybackAck(String taskId, String sessionId, String playerId, long leaseGeneration,
                          State state, Instant occurredAt, String errorCode) {
    public enum State { STARTED, COMPLETED, FAILED, CANCELLED }
}
