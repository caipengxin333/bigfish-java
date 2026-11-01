package cn.bigfish.contract;

import java.time.Instant;
import java.util.List;

/** Bounded, metadata-only view. No comments, usernames, external event IDs or provider response bodies. */
public record RuntimeDiagnosticsView(String sessionId, String state, Instant capturedAt, List<String> waiting,
                                     Instant lastReceivedAt, Instant lastModelSuccessAt, Instant lastSpeechSuccessAt,
                                     Instant lastPlaybackSuccessAt, Trace lastFailure, List<Trace> recent,
                                     long taskEvictions, long historyEvictions, int audioAssets, int dedupEntries,
                                     Worker modelWorker, Worker speechWorker) {
    public record Trace(long sequence, String taskId, String eventType, long configVersion, String stage,
                        String reason, Instant at, long previousStageMs, long taskElapsedMs) {}
    public record Worker(int threads, int active, int queued, long completed) {}
}
