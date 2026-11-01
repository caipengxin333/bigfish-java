package cn.bigfish.playback;

import cn.bigfish.contract.*;
import cn.bigfish.scheduler.BoundedPriorityQueue;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.Stage;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.Reason;
import java.time.*;
import java.util.*;

/** All protocol state lives here. Transport send() must be non-blocking and bounded. */
public final class PlaybackCoordinator {
    public interface Peer {
        String id();
        boolean send(String type, String sessionId, Object payload);
        void close();
    }
    private record Queued(PlaybackTask task, Duration duration) implements BoundedPriorityQueue.Item {
        public int priority() { return task.priority(); }
        public Instant expiresAt() { return task.expiresAt(); }
    }
    public record Status(boolean connected, boolean ready, long generation, int queued, boolean playing, long epoch) {}
    private final Clock clock;
    private final AudioAssets assets;
    private final BoundedPriorityQueue<Queued> queue;
    private RuntimeDiagnostics diagnostics;
    private Reason revocation = Reason.SESSION_STOPPED;
    private String sessionId = "";
    private Peer player;
    private String playerId;
    private String retiringPeer;
    private Instant fencedUntil = Instant.MIN;
    private long generation, epoch;
    private Instant leaseUntil = Instant.MIN, ackUntil = Instant.MIN;
    private boolean ready, started;
    private PlaybackTask current;
    private Duration currentDuration=Duration.ofSeconds(25);
    public PlaybackCoordinator(Clock clock, AudioAssets assets) {
        this.clock = clock; this.assets = assets;
        diagnostics = new RuntimeDiagnostics(clock);
        queue = BoundedPriorityQueue.withReasons((q,reason) -> {
            assets.remove(q.task().taskId());
            trace(q.task(),reason==BoundedPriorityQueue.DiscardReason.CLEARED ? Stage.CANCELLED : Stage.DROPPED,
                switch(reason) {
                    case EXPIRED -> Reason.TASK_EXPIRED;
                    case FULL -> Reason.QUEUE_FULL;
                    case CAPACITY_REDUCED -> Reason.CAPACITY_REDUCED;
                    case INVALIDATED -> Reason.PLAYER_CHANGED;
                    case CLEARED -> revocation;
                });
        });
    }
    /** Installed once by the engine before a session starts; no listener calls back into the engine. */
    public synchronized void trackWith(RuntimeDiagnostics recorder) {
        if (!sessionId.isEmpty()) throw new IllegalStateException("SESSION_ACTIVE");
        diagnostics = Objects.requireNonNull(recorder);
    }
    public synchronized void begin(String id) {
        stop("NEW_SESSION");
        sessionId = id;
        diagnostics.begin(id);
        assets.begin(id);
    }
    public synchronized void hello(Peer peer, String id) {
        expireLease();
        if (sessionId.isEmpty()) { peer.send("SESSION_STOPPED", "", Map.of("reason", "NO_SESSION")); return; }
        if (player != null || (retiringPeer != null && fencedUntil.isAfter(clock.instant()))) {
            // Repeating HELLO cannot reset READY or acquire a second lease, even with the same playerId.
            peer.send("PLAYER_REJECTED", sessionId, Map.of("reason", "PLAYER_ALREADY_ACTIVE"));
            return;
        }
        player = peer; playerId = id; generation++; ready = false;
        leaseUntil = clock.instant().plusSeconds(15);
        send("PLAYER_LEASE", Map.of("playerId", id, "leaseGeneration", generation,
            "expiresAt", leaseUntil, "heartbeatIntervalMs", 5000));
    }
    public synchronized void heartbeat(Peer peer, String session, String id, long lease) {
        if (!owns(peer, session, id, lease)) return;
        leaseUntil = clock.instant().plusSeconds(15);
        send("HEARTBEAT_ACK", Map.of("playerId", playerId, "leaseGeneration", generation, "expiresAt", leaseUntil));
    }
    public synchronized void ready(Peer peer, String session, String id, long lease) {
        if (owns(peer, session, id, lease)) ready = true;
    }
    public synchronized void acknowledge(Peer peer, PlaybackAck ack) {
        if (!owns(peer, ack.sessionId(), ack.playerId(), ack.leaseGeneration()) || current == null
            || !current.taskId().equals(ack.taskId())) return;
        if (!current.expiresAt().isAfter(clock.instant()) || !ackUntil.isAfter(clock.instant())) {
            revoke("PLAYBACK_TIMEOUT"); return;
        }
        switch (ack.state()) {
            case STARTED -> {
                if (!started) {
                    started = true; ackUntil = clock.instant().plus(currentDuration.plusSeconds(5));
                    trace(current,Stage.STARTED,Reason.NONE);
                }
            }
            case COMPLETED -> { if (started) finish(Stage.COMPLETED,Reason.NONE); }
            // Never store the client-controlled errorCode or timestamp.
            case FAILED -> finish(Stage.FAILED,Reason.PLAYER_FAILED);
            case CANCELLED -> finish(Stage.CANCELLED,Reason.PLAYER_CANCELLED);
        }
    }
    public synchronized boolean enqueue(PlaybackTask task, AudioAssets.Content content, int capacity, long expectedEpoch) {
        return enqueue(task,content,capacity,expectedEpoch,Duration.ofSeconds(25));
    }
    public synchronized boolean enqueue(PlaybackTask task, AudioAssets.Content content, int capacity, long expectedEpoch, Duration duration) {
        expireLease();
        if (duration==null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofSeconds(25))>0) {
            trace(task,Stage.DROPPED,Reason.INVALID_AUDIO); return false;
        }
        if (!task.sessionId().equals(sessionId) || epoch != expectedEpoch) {
            trace(task,Stage.CANCELLED,Reason.PLAYER_CHANGED); return false;
        }
        if (!isReady()) { trace(task,Stage.DROPPED,Reason.PLAYER_NOT_READY); return false; }
        if (!assets.register(task.taskId(), sessionId, task.expiresAt(), content)) {
            trace(task,Stage.DROPPED,task.expiresAt().isAfter(clock.instant()) ? Reason.AUDIO_REJECTED : Reason.TASK_EXPIRED); return false;
        }
        boolean accepted = queue.offer(new Queued(task,duration), capacity, clock.instant());
        if (accepted) trace(task,Stage.PLAYBACK_WAIT,Reason.NONE);
        return accepted;
    }
    public synchronized void tick(int capacity) {
        expireLease();
        Instant now = clock.instant();
        queue.trim(capacity, now);
        if (current != null && (!current.expiresAt().isAfter(now) || !ackUntil.isAfter(now))) {
            // Do not send another task to a player that may still be speaking.
            revoke("PLAYBACK_TIMEOUT");
        }
        if (current == null && isReady()) {
            var next = queue.poll(now);
            // Reserve both the STARTED acknowledgement budget and the completion grace period.
            while (next != null && !now.plus(next.duration()).plusSeconds(10).isBefore(next.task().expiresAt())) {
                assets.remove(next.task().taskId()); trace(next.task(),Stage.DROPPED,Reason.INSUFFICIENT_PLAYBACK_TTL);
                next=queue.poll(now);
            }
            if (next != null) {
                currentDuration=next.duration(); current = next.task(); started = false; ackUntil = now.plusSeconds(5);
                trace(current,Stage.PLAYBACK_SENT,Reason.NONE);
                send("PLAY_TASK", current);
            }
        }
    }
    public synchronized void disconnected(Peer peer) {
        if (player != null && player.id().equals(peer.id())) revoke("PLAYER_DISCONNECTED");
        // Transport has observed the close. Until then the previous client may still be audible.
        if (peer.id().equals(retiringPeer)) { retiringPeer = null; fencedUntil = Instant.MIN; }
    }
    public synchronized boolean deliveryAllowed(Peer peer, String taskId) {
        expireLease();
        return player != null && player.id().equals(peer.id()) && current != null
            && current.taskId().equals(taskId) && current.expiresAt().isAfter(clock.instant());
    }
    public synchronized void stop(String reason) {
        if (player != null) player.send("SESSION_STOPPED", sessionId, Map.of("reason", reason));
        revoke(reason);
        sessionId = "";
    }
    public synchronized Status status() {
        expireLease();
        return new Status(player != null, isReady(), generation, queue.size(), current != null, epoch);
    }
    public synchronized boolean hasStarted() { return current!=null && started; }
    private boolean owns(Peer peer, String session, String id, long lease) {
        expireLease();
        return player != null && player.id().equals(peer.id()) && sessionId.equals(session)
            && playerId.equals(id) && generation == lease;
    }
    private boolean isReady() { return player != null && ready; }
    private void expireLease() { if (player != null && !leaseUntil.isAfter(clock.instant())) revoke("LEASE_EXPIRED"); }
    private void finish(Stage stage, Reason reason) {
        trace(current,stage,reason); assets.remove(current.taskId()); current = null; started = false;
    }
    private void revoke(String reason) {
        revocation = switch (reason) {
            case "PLAYBACK_TIMEOUT" -> current!=null && !current.expiresAt().isAfter(clock.instant()) ? Reason.TASK_EXPIRED
                : started ? Reason.COMPLETION_TIMEOUT : Reason.START_ACK_TIMEOUT;
            case "PLAYER_DISCONNECTED" -> Reason.PLAYER_DISCONNECTED;
            case "LEASE_EXPIRED" -> Reason.LEASE_EXPIRED;
            case "PLAYER_BACKPRESSURE" -> Reason.PLAYER_BACKPRESSURE;
            default -> Reason.SESSION_STOPPED;
        };
        if (current != null) trace(current,revocation==Reason.START_ACK_TIMEOUT || revocation==Reason.COMPLETION_TIMEOUT
            ? Stage.FAILED : Stage.CANCELLED,revocation);
        if (current != null && player != null) player.send("CANCEL_TASK", sessionId, Map.of("taskId", current.taskId(), "reason", reason));
        if (player != null) {
            retiringPeer = player.id(); fencedUntil = leaseUntil;
            player.close();
        }
        player = null; playerId = null; ready = false; current = null; started = false;
        queue.clear(); assets.clear(); epoch++;
    }
    private void trace(PlaybackTask task, Stage stage, Reason reason) {
        diagnostics.mark(task.sessionId(),task.taskId(),stage,reason);
    }
    private void send(String type, Object payload) {
        if (player != null && !player.send(type, sessionId, payload)) revoke("PLAYER_BACKPRESSURE");
    }
}
