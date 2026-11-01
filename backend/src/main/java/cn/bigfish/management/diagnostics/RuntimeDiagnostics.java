package cn.bigfish.management.diagnostics;

import cn.bigfish.contract.LiveEvent;
import java.time.*;
import java.util.*;

/** Internal, bounded metadata only. Never accepts payloads, provider messages or client ACK error text.
 * Lock order: engine -> playback -> diagnostics. This recorder never calls back into either owner. */
public final class RuntimeDiagnostics {
    public static final int MAX_HISTORY = 1024, MAX_TASKS = 512;
    public static final Duration RETENTION = Duration.ofMinutes(5);
    public enum Stage {
        RECEIVED, MODEL_WAIT, MODEL_STARTED, MODEL_COMPLETED, SPEECH_WAIT, SPEECH_STARTED,
        SPEECH_COMPLETED, PLAYBACK_WAIT, PLAYBACK_SENT, STARTED, COMPLETED, SKIPPED, DROPPED, CANCELLED, FAILED
    }
    public enum Reason {
        NONE, LIKE_NO_SPEECH, DUPLICATE, INVALID_EVENT, WELCOME_BUSY, WELCOME_INTERVAL, GIFT_MERGED,
        QUEUE_FULL, CAPACITY_REDUCED, TASK_EXPIRED, INSUFFICIENT_PLAYBACK_TTL, SESSION_STOPPED,
        PLAYER_CHANGED, PLAYER_NOT_READY, PLAYER_DISCONNECTED, LEASE_EXPIRED, PLAYER_BACKPRESSURE,
        START_ACK_TIMEOUT, COMPLETION_TIMEOUT, PLAYER_FAILED, PLAYER_CANCELLED, AUDIO_REJECTED,
        MODEL_EMPTY_OUTPUT, MODEL_FAILED, SPEECH_FAILED, OVERALL_TIMEOUT, RATE_LIMITED, SERVER_ERROR,
        CONNECT_TIMEOUT, READ_TIMEOUT, AUTHENTICATION_FAILED, INSUFFICIENT_BALANCE, PERMISSION_DENIED,
        MODEL_NOT_FOUND, INVALID_RESPONSE, OUTPUT_LIMIT, PROVIDER_UNAVAILABLE, TOKEN_EXPIRED,
        TOKEN_REFRESH_FAILED, INVALID_AUDIO, WORKER_BUSY, PROVIDER_CANCELLED, RUNTIME_FAILED
    }
    public record Trace(long sequence, String sessionId, String taskId, LiveEvent.Type eventType,
                        long configVersion, Stage stage, Reason reason, Instant at,
                        long previousStageMs, long taskElapsedMs) {}
    public record Task(String taskId, LiveEvent.Type eventType, long configVersion, Stage stage, Reason reason,
                       Instant receivedAt, Instant updatedAt, long stageAgeMs, long taskAgeMs) {}
    public record Snapshot(String sessionId, Instant capturedAt, Instant lastReceivedAt, Instant lastModelSuccessAt,
                           Instant lastSpeechSuccessAt, Instant lastPlaybackSuccessAt, Trace lastFailure,
                           long taskEvictions, long historyEvictions, Map<Stage,Long> transitions,
                           Map<Reason,Long> reasons, List<Trace> history, List<Task> tasks) {}
    private record Entry(LiveEvent.Type type, long version, Instant receivedAt, Trace latest) {}
    private final Clock clock;
    private final LinkedHashMap<String,Entry> tasks = new LinkedHashMap<>();
    private final ArrayDeque<Trace> history = new ArrayDeque<>();
    private final EnumMap<Stage,Long> transitions = new EnumMap<>(Stage.class);
    private final EnumMap<Reason,Long> reasons = new EnumMap<>(Reason.class);
    private String session = "";
    private Instant received, modelSuccess, speechSuccess, playbackSuccess;
    private Trace lastFailure;
    private long sequence, taskEvictions, historyEvictions;
    public RuntimeDiagnostics(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    public synchronized void begin(String sessionId) {
        session = Objects.requireNonNull(sessionId); tasks.clear(); history.clear(); transitions.clear(); reasons.clear();
        received = modelSuccess = speechSuccess = playbackSuccess = null; lastFailure = null;
        sequence = taskEvictions = historyEvictions = 0;
    }
    /** IDs are server-generated task UUIDs; external event IDs/names are deliberately not retained. */
    public synchronized void received(String sessionId, String taskId, LiveEvent.Type type, long version) {
        if (!session.equals(sessionId) || taskId == null || taskId.length() > 128 || tasks.containsKey(taskId)) return;
        prune();
        if (tasks.size() >= MAX_TASKS) {
            String victim = tasks.entrySet().stream().filter(e -> terminal(e.getValue().latest().stage()))
                .map(Map.Entry::getKey).findFirst().orElse(tasks.keySet().iterator().next());
            tasks.remove(victim); taskEvictions++;
        }
        var now = clock.instant(); received = now;
        tasks.put(taskId,new Entry(type,version,now,null));
        mark(sessionId,taskId,Stage.RECEIVED,Reason.NONE);
    }
    /** Missing/evicted/stale/terminal tasks cannot be recreated by late results. */
    public synchronized boolean mark(String sessionId, String taskId, Stage stage, Reason reason) {
        if (!session.equals(sessionId)) return false;
        prune(); var entry = tasks.get(taskId);
        if (entry == null || (entry.latest()!=null && terminal(entry.latest().stage()))) return false;
        if (entry.latest()!=null && entry.latest().stage()==stage && entry.latest().reason()==reason) return false;
        var now = clock.instant();
        var trace = new Trace(++sequence,session,taskId,entry.type(),entry.version(),stage,reason,now,
            age(entry.latest()==null ? entry.receivedAt() : entry.latest().at(),now),age(entry.receivedAt(),now));
        tasks.put(taskId,new Entry(entry.type(),entry.version(),entry.receivedAt(),trace));
        if (history.size() >= MAX_HISTORY) { history.removeFirst(); historyEvictions++; }
        history.addLast(trace); transitions.merge(stage,1L,Long::sum); reasons.merge(reason,1L,Long::sum);
        if (stage==Stage.MODEL_COMPLETED) modelSuccess=now;
        if (stage==Stage.SPEECH_COMPLETED) speechSuccess=now;
        if (stage==Stage.COMPLETED) playbackSuccess=now;
        if (stage==Stage.FAILED) lastFailure=trace;
        return true;
    }
    public synchronized Snapshot snapshot() {
        prune(); var now=clock.instant();
        var views=tasks.entrySet().stream().map(e -> {
            var v=e.getValue(); var t=v.latest();
            return new Task(e.getKey(),v.type(),v.version(),t.stage(),t.reason(),v.receivedAt(),t.at(),age(t.at(),now),age(v.receivedAt(),now));
        }).toList();
        return new Snapshot(session,now,received,modelSuccess,speechSuccess,playbackSuccess,lastFailure,
            taskEvictions,historyEvictions,Map.copyOf(transitions),Map.copyOf(reasons),List.copyOf(history),views);
    }
    private void prune() {
        Instant cutoff=clock.instant().minus(RETENTION);
        while (!history.isEmpty() && !history.peekFirst().at().isAfter(cutoff)) history.removeFirst();
        tasks.entrySet().removeIf(e -> !e.getValue().receivedAt().isAfter(cutoff));
        if (lastFailure!=null && !lastFailure.at().isAfter(cutoff)) lastFailure=null;
    }
    private static long age(Instant from, Instant to) { return Math.max(0,Duration.between(from,to).toMillis()); }
    private static boolean terminal(Stage s) { return s==Stage.COMPLETED || s==Stage.SKIPPED || s==Stage.DROPPED || s==Stage.CANCELLED || s==Stage.FAILED; }
}
