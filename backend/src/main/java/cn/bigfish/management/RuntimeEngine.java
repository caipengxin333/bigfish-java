package cn.bigfish.management;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
import cn.bigfish.conversation.GiftTemplates;
import cn.bigfish.event.*;
import cn.bigfish.playback.*;
import cn.bigfish.scheduler.BoundedPriorityQueue;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.Stage;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.Reason;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Internal engine; SessionService maps its state to the public management contract. */
public final class RuntimeEngine implements EventIngress {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    public record Status(String sessionId, State state, String mode, String conversationMode, String speechMode,
                         long savedConfigVersion, long appliedConfigVersion, String roomId, int comments,
                         int templates, int synthesis, boolean modelActive, boolean speechActive,
                         long dropped, long failures, PlaybackCoordinator.Status playback, String lastError, String lastErrorAt) {}
    private record Job(String id, LiveEvent event, ConfigSnapshot config, Instant expiresAt, Instant readyAt,
                       int priority, long epoch, String text, AtomicBoolean failed) implements BoundedPriorityQueue.Item {
        Job withText(String value) { return new Job(id,event,config,expiresAt,readyAt,priority,epoch,value,failed); }
    }
    public enum WaitReason { STOPPED, NO_MESSAGES, NO_PENDING_TASKS, PLAYER_NOT_READY, MODEL_WAIT, SPEECH_WAIT, PLAYBACK_WAIT, PLAYING, WORKER_BUSY }
    public record WorkerStatus(int threads, int active, int queued, long completed) {}
    public record DiagnosticsView(Status runtime, List<WaitReason> waiting, WorkerStatus modelWorker,
                                  WorkerStatus speechWorker, int dedupEntries, int audioAssets, RuntimeDiagnostics.Snapshot trace) {}
    private static final Logger log = LoggerFactory.getLogger(RuntimeEngine.class);
    private final ConfigStore configs;
    private final ConversationPort mock;
    private final Supplier<ConversationPort> realFactory;
    private final SpeechPort testSpeech;
    private SpeechPort speech;
    private String speechMode="LOCAL_TEST_TONE", lastError="", lastErrorAt="";
    private final PlaybackCoordinator playback;
    private final AudioAssets assets;
    private final Clock clock;
    private final Duration modelTimeout, speechTimeout;
    private final RuntimeDiagnostics diagnostics;
    private long appliedVersion, dropped, failures;
    private final RecentEvents recent = new RecentEvents();
    private final BoundedPriorityQueue<Job> comments = BoundedPriorityQueue.withReasons(this::discarded);
    private final BoundedPriorityQueue<Job> templates = BoundedPriorityQueue.withReasons(this::discarded);
    private final BoundedPriorityQueue<Job> synthesis = BoundedPriorityQueue.withReasons(this::discarded);
    // A blocked port cannot consume unlimited worker threads or accumulate executor tasks.
    private final ThreadPoolExecutor modelWorker = worker("conversation");
    private final ThreadPoolExecutor speechWorker = worker("speech");
    private State state = State.STOPPED;
    private String sessionId = "", roomId = "", mode = "MOCK", conversationMode = "MOCK";
    private ConversationPort conversation;
    private Job modelJob, speechJob;
    private Future<?> modelCall, speechCall;
    private Instant modelUntil, speechUntil, lastWelcome = Instant.MIN;
    private boolean closed, terminalError;

    public RuntimeEngine(ConfigStore configs, ConversationPort mock, Supplier<ConversationPort> realFactory,
                         SpeechPort speech, PlaybackCoordinator playback, AudioAssets assets, Clock clock,
                         Duration modelTimeout, Duration speechTimeout) {
        this.configs = configs; this.mock = mock; this.realFactory = realFactory; this.speech = speech; this.testSpeech=speech;
        this.playback = playback; this.assets = assets; this.clock = clock;
        diagnostics = new RuntimeDiagnostics(clock); playback.trackWith(diagnostics);
        this.modelTimeout = modelTimeout; this.speechTimeout = speechTimeout;
        configs.current();
    }
    public synchronized String startMock() { return startMock(false); }
    /** Real calls require both this explicit choice and the server-side credential/paid-call gate. */
    public synchronized String startMock(boolean useDeepSeek) { return startMock(useDeepSeek,testSpeech,"LOCAL_TEST_TONE"); }
    public synchronized String startMock(boolean useDeepSeek, SpeechPort selectedSpeech, String selectedSpeechMode) {
        return start(StartSessionRequest.Mode.MOCK,useDeepSeek,selectedSpeech,selectedSpeechMode);
    }
    public synchronized String start(StartSessionRequest.Mode selectedMode, boolean useDeepSeek,
                                     SpeechPort selectedSpeech, String selectedSpeechMode) {
        if (closed || (state != State.STOPPED && state != State.FAILED)) throw new IllegalStateException("SESSION_ACTIVE");
        state = State.STARTING;
        conversation = null; terminalError=false; lastError=""; lastErrorAt="";
        sessionId = UUID.randomUUID().toString(); mode = selectedMode.name();
        roomId = selectedMode == StartSessionRequest.Mode.MOCK ? "mock-room" : "";
        conversationMode = useDeepSeek ? "DEEPSEEK" : "MOCK"; speechMode=selectedSpeechMode;
        try {
            // Even an allocation failure belongs to this attempt, never to the previous session.
            recent.clear(); playback.begin(sessionId);
            conversation = useDeepSeek ? realFactory.get() : mock;
            speech=Objects.requireNonNull(selectedSpeech);
            appliedVersion = configs.snapshot().version(); lastWelcome = Instant.MIN;
            state = State.RUNNING;
            return sessionId;
        } catch (RuntimeException error) {
            recordTerminalError("PROVIDER_UNAVAILABLE"); failures++;
            stop(sessionId); // Also close a model allocated before a later startup step failed.
            state = State.FAILED; throw new IllegalStateException("PROVIDER_UNAVAILABLE");
        }
    }
    /** Bind once from the resolver, before WSS dispatch. Reconnects must retain this broadcast ID. */
    public synchronized boolean bindDouyinRoom(String id, String internalRoomId) {
        if (state != State.RUNNING || !sessionId.equals(id) || !mode.equals("DOUYIN")
            || internalRoomId == null || !internalRoomId.matches("[0-9]{1,30}")) return false;
        if (!roomId.isEmpty()) return roomId.equals(internalRoomId);
        roomId = internalRoomId;
        return true;
    }
    public synchronized void collectorFailed(String id) {
        if (!sessionId.equals(id) || state != State.RUNNING) return;
        recordTerminalError("COLLECTOR_FAILED");
        stop(id);
        state = State.FAILED;
        failures++;
    }
    public synchronized void stop(String id) {
        if (!sessionId.equals(id) || state == State.STOPPED) return;
        state = State.STOPPING; // Close ingress before cancelling any asynchronous work.
        cancelModel(Reason.SESSION_STOPPED); cancelSpeech(Reason.SESSION_STOPPED);
        comments.clear(); templates.clear(); synthesis.clear(); recent.clear();
        playback.stop("SESSION_STOPPED");
        ConversationPort retiringConversation=conversation; conversation=null;
        if (retiringConversation instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { recordError("MODEL_CLOSE_FAILED"); }
        }
        state = State.STOPPED;
    }
    @Override public synchronized Result accept(LiveEvent input) {
        if (state != State.RUNNING || input == null || !sessionId.equals(input.sessionId())) return Result.REJECTED_SESSION;
        Instant now = clock.instant();
        String taskId = UUID.randomUUID().toString();
        ConfigSnapshot config = configs.snapshot();
        diagnostics.received(sessionId,taskId,input.type(),config.version());
        LiveEvent event;
        try { event = EventValidator.normalize(input, now); }
        catch (IllegalArgumentException e) { diagnostics.mark(sessionId,taskId,Stage.DROPPED,Reason.INVALID_EVENT); return Result.INVALID; }
        if (!roomId.equals(event.roomId())) { diagnostics.mark(sessionId,taskId,Stage.DROPPED,Reason.INVALID_EVENT); return Result.INVALID; }
        if (recent.contains(event.eventId(), now)) { diagnostics.mark(sessionId,taskId,Stage.SKIPPED,Reason.DUPLICATE); return Result.DUPLICATE; }
        var settings = config.settings();
        var player = playback.status();
        if (event.type() == LiveEvent.Type.LIKE) {
            diagnostics.mark(sessionId,taskId,Stage.SKIPPED,Reason.LIKE_NO_SPEECH);
            recent.remember(event.eventId(),now); return Result.ACCEPTED;
        }
        boolean welcome = event.type() == LiveEvent.Type.FOLLOW || event.type() == LiveEvent.Type.ENTRANCE;
        if (welcome && (busy() || lastWelcome.plusSeconds(settings.welcomeIntervalSeconds()).isAfter(now))) {
            diagnostics.mark(sessionId,taskId,Stage.SKIPPED,busy() ? Reason.WELCOME_BUSY : Reason.WELCOME_INTERVAL);
            dropped++; recent.remember(event.eventId(),now); return Result.ACCEPTED;
        }
        int priority = switch (event.type()) {
            case COMMENT -> settings.priorities().comment(); case GIFT -> settings.priorities().gift();
            case FOLLOW -> settings.priorities().follow(); default -> settings.priorities().entrance();
        };
        long ttl = event.type() == LiveEvent.Type.COMMENT ? 60 : event.type() == LiveEvent.Type.GIFT ? 120 : 40;
        Job job = new Job(taskId,event,config,now.plusSeconds(ttl),
            event.type() == LiveEvent.Type.GIFT ? now.plusMillis(150) : now,priority,player.epoch(),null,new AtomicBoolean());
        boolean accepted;
        if (event.type() == LiveEvent.Type.GIFT && mergeGift(job, now)) {
            trace(job,Stage.SKIPPED,Reason.GIFT_MERGED); accepted = true;
        } else {
            accepted = (event.type() == LiveEvent.Type.COMMENT ? comments : templates).offer(job,settings.synthesisQueueCapacity(),now);
            if (accepted) trace(job,event.type()==LiveEvent.Type.COMMENT ? Stage.MODEL_WAIT : Stage.SPEECH_WAIT,Reason.NONE);
        }
        if (!accepted) return Result.REJECTED_FULL;
        if (welcome) lastWelcome = now;
        appliedVersion = config.version(); recent.remember(event.eventId(),now);
        return Result.ACCEPTED;
    }
    private boolean mergeGift(Job job, Instant now) {
        // quantity is already incremental by contract; never subtract it a second time.
        var e = job.event();
        if (e.userId().isBlank()) return false;
        Job previous = templates.find(j -> j.event().type() == LiveEvent.Type.GIFT && j.readyAt().isAfter(now)
            && j.config().version() == job.config().version() && j.epoch() == job.epoch()
            && j.event().userId().equals(e.userId()) && j.event().payload().get("giftId").equals(e.payload().get("giftId"))
            && j.event().payload().get("comboId").equals(e.payload().get("comboId")));
        if (previous == null) return false;
        long quantity = ((Number)previous.event().payload().get("quantity")).longValue() + ((Number)e.payload().get("quantity")).longValue();
        if (quantity > 1_000_000) return false;
        var p = new HashMap<>(previous.event().payload()); p.put("quantity",quantity);
        var merged = new LiveEvent(1,previous.event().eventId(),sessionId,roomId,LiveEvent.Type.GIFT,e.userId(),
            previous.event().username(),previous.event().receivedAt(),Collections.unmodifiableMap(p));
        templates.replace(previous,new Job(previous.id(),merged,previous.config(),previous.expiresAt(),previous.readyAt(),
            previous.priority(),previous.epoch(),null,previous.failed()));
        return true;
    }
    @Scheduled(fixedDelay = 25)
    public synchronized void tick() {
        try { advance(); }
        catch (RuntimeException error) {
            recordTerminalError("RUNTIME_FAILED");
            failures++; log.warn("runtime_failure type={} sessionId={}", error.getClass().getSimpleName(),sessionId);
            stop(sessionId); state = State.FAILED;
        }
    }
    private void advance() {
        if (state != State.RUNNING) return;
        Instant now = clock.instant();
        var settings = configs.snapshot().settings();
        int capacity = settings.synthesisQueueCapacity();
        playback.tick(settings.playbackQueueCapacity());
        var player = playback.status();
        comments.trim(capacity,now); templates.trim(capacity,now); synthesis.trim(capacity,now);
        if (modelJob != null && !valid(modelJob)) cancelModel(invalidReason(modelJob));
        if (speechJob != null && !valid(speechJob)) cancelSpeech(invalidReason(speechJob));
        if (modelJob != null && !modelUntil.isAfter(now)) { failed("MODEL",modelJob,new TimeoutException()); cancelModel(Reason.OVERALL_TIMEOUT); }
        if (speechJob != null && !speechUntil.isAfter(now)) { failed("SPEECH",speechJob,new TimeoutException()); cancelSpeech(Reason.OVERALL_TIMEOUT); }
        // Do not spend model/TTS work when there is no ready player. On disconnect, purge epoch-bound work.
        comments.discardWhere(j -> j.epoch() != player.epoch());
        templates.discardWhere(j -> j.epoch() != player.epoch());
        synthesis.discardWhere(j -> j.epoch() != player.epoch());
        if (!player.ready()) return;
        Job template;
        while ((template = templates.poll(now)) != null) {
            if (valid(template)) synthesis.offer(template.withText(GiftTemplates.render(template.event())),capacity,now);
            else discardInvalid(template);
        }
        // A cancelled noncompliant port may still occupy its one worker. Keep pending work bounded by queue/TTL.
        if (modelJob == null && modelWorker.getActiveCount()==0 && (modelJob = nextValid(comments,now)) != null) startModel(modelJob);
        if (speechJob == null && speechWorker.getActiveCount()==0 && (speechJob = nextValid(synthesis,now)) != null) startSpeech(speechJob);
    }
    private Job nextValid(BoundedPriorityQueue<Job> queue, Instant now) {
        Job job;
        while ((job = queue.poll(now)) != null) { if (valid(job)) return job; discardInvalid(job); }
        return null;
    }
    private void startModel(Job job) {
        Duration deadline = remaining(job,modelTimeout); modelUntil = clock.instant().plus(deadline);
        ConversationPort port = conversation;
        try {
            modelCall = modelWorker.submit(() -> {
                trace(job,Stage.MODEL_STARTED,Reason.NONE);
                StringBuilder reply = new StringBuilder();
                try {
                    port.reply(job.id(),job.event(),job.config(),deadline, text -> {
                        synchronized (reply) {
                            if (text != null && reply.length() + text.length() <= 2000) reply.append(text);
                            else throw new IllegalArgumentException("MODEL_OUTPUT_LIMIT");
                        }
                    }).toCompletableFuture().get(deadline.toMillis(),TimeUnit.MILLISECONDS);
                    String value; synchronized (reply) { value = EventValidator.safe(reply.toString(),2000); }
                    synchronized (this) {
                        if (modelJob == job && valid(job)) {
                            if (value.isBlank()) throw new EmptyModelReply();
                            trace(job,Stage.MODEL_COMPLETED,Reason.NONE);
                            if (job.config().settings().readComment()) value = job.event().payload().get("content") + "。" + value;
                            if (synthesis.offer(job.withText(value),configs.snapshot().settings().synthesisQueueCapacity(),clock.instant()))
                                trace(job,Stage.SPEECH_WAIT,Reason.NONE);
                        } else trace(job,Stage.CANCELLED,invalidReason(job));
                    }
                } catch (Exception error) { failed("MODEL",job,error); cancelPort("MODEL",job,() -> port.cancel(job.id())); }
                finally { synchronized (this) { if (modelJob == job) { modelJob = null; modelCall = null; } } }
            });
        } catch (RejectedExecutionException error) {
            modelJob = null; modelCall = null;
            if (comments.restoreFirst(job,configs.snapshot().settings().synthesisQueueCapacity(),clock.instant())) trace(job,Stage.MODEL_WAIT,Reason.WORKER_BUSY);
        }
    }
    private void startSpeech(Job job) {
        Duration deadline = remaining(job,"ALIYUN_NLS".equals(speechMode) ? Duration.ofSeconds(10) : speechTimeout); speechUntil = clock.instant().plus(deadline);
        SpeechPort port=speech;
        try {
            speechCall = speechWorker.submit(() -> {
                trace(job,Stage.SPEECH_STARTED,Reason.NONE);
                SpeechPort.AudioAsset asset=null;
                boolean releaseAttempted=false;
                try {
                    asset = port.synthesize(job.id(),job.text(),job.config().settings().voice(),deadline)
                        .toCompletableFuture().get(deadline.toMillis(),TimeUnit.MILLISECONDS);
                    if(asset.duration()==null || asset.duration().isNegative() || asset.duration().isZero()
                        || asset.duration().compareTo(Duration.ofSeconds(25))>0)
                        throw new cn.bigfish.speech.SpeechFailure(cn.bigfish.speech.SpeechFailure.Code.AUDIO_TOO_LONG);
                    var content = assets.load(asset);
                    // Release before publishing; a cleanup failure cannot follow a task already playing.
                    releaseAttempted=true; port.release(asset);
                    synchronized (this) {
                        if (speechJob == job && valid(job)) {
                            trace(job,Stage.SPEECH_COMPLETED,Reason.NONE);
                            var task = new PlaybackTask(job.id(),job.event().sessionId(),job.config().version(),job.event().type().name(),
                                job.text(),"/api/v1/audio/" + job.id(),job.priority(),job.expiresAt());
                            if (!playback.enqueue(task,content,configs.snapshot().settings().playbackQueueCapacity(),job.epoch(),asset.duration())) dropped++;
                        } else trace(job,Stage.CANCELLED,invalidReason(job));
                    }
                } catch (Exception error) { failed("SPEECH",job,error); cancelPort("SPEECH",job,() -> port.cancel(job.id())); }
                finally {
                    try { if(asset!=null && !releaseAttempted) port.release(asset); }
                    catch(RuntimeException error) { failed("SPEECH",job,error); }
                    synchronized (this) { if (speechJob == job) { speechJob = null; speechCall = null; } }
                }
            });
        } catch (RejectedExecutionException error) {
            speechJob = null; speechCall = null;
            if (synthesis.restoreFirst(job,configs.snapshot().settings().synthesisQueueCapacity(),clock.instant())) trace(job,Stage.SPEECH_WAIT,Reason.WORKER_BUSY);
        }
    }
    private static final class EmptyModelReply extends RuntimeException {}
    private synchronized void failed(String type, Job job, Exception error) {
        if (type.equals("MODEL") ? modelJob!=job : speechJob!=job) return;
        if (!valid(job)) { trace(job,Stage.CANCELLED,invalidReason(job)); return; }
        if (job.failed().get()) return;
        String code=type+"_FAILED";
        Reason reason=type.equals("MODEL") ? Reason.MODEL_FAILED : Reason.SPEECH_FAILED;
        Throwable cause=error;
        for(int depth=0;cause!=null && depth<8;depth++,cause=cause.getCause()) {
            if(cause instanceof cn.bigfish.conversation.DeepSeekFailure known) { code="MODEL_"+known.diagnostic().code().name(); break; }
            if(cause instanceof cn.bigfish.speech.SpeechFailure known) { code="NLS_"+known.code().name(); break; }
            if(cause instanceof TimeoutException) { code=type+"_OVERALL_TIMEOUT"; reason=Reason.OVERALL_TIMEOUT; break; }
            if(cause instanceof EmptyModelReply) { code="MODEL_EMPTY_OUTPUT"; reason=Reason.MODEL_EMPTY_OUTPUT; break; }
            if(cause instanceof CancellationException || cause instanceof InterruptedException) reason=Reason.PROVIDER_CANCELLED;
        }
        if(cause instanceof cn.bigfish.conversation.DeepSeekFailure known) reason=providerReason(known.diagnostic().code().name(),reason);
        if(cause instanceof cn.bigfish.speech.SpeechFailure known) reason=providerReason(known.code().name(),reason);
        if (!job.failed().compareAndSet(false,true)) return;
        if (reason==Reason.PROVIDER_CANCELLED) {
            // Seal the task outcome so cancellation/release callbacks cannot turn it into a failure.
            trace(job,Stage.CANCELLED,reason); return;
        }
        failures++; recordError(code);
        trace(job,Stage.FAILED,reason);
        log.warn("task_failure type={} errorType={} taskId={} elapsedMs={}",type,error.getClass().getSimpleName(),
            job.id(),Duration.between(job.event().receivedAt(),clock.instant()).toMillis());
    }
    private static Reason providerReason(String code, Reason fallback) {
        try { return Reason.valueOf(code); }
        catch (IllegalArgumentException ignored) {
            return switch(code) {
                case "DEADLINE_EXCEEDED" -> Reason.OVERALL_TIMEOUT;
                case "INVALID_WAV", "AUDIO_TOO_LARGE", "AUDIO_TOO_LONG", "AUDIO_SILENT" -> Reason.INVALID_AUDIO;
                case "TOKEN_REJECTED" -> Reason.AUTHENTICATION_FAILED;
                case "TOKEN_REFRESH_TIMEOUT" -> Reason.TOKEN_REFRESH_FAILED;
                case "CANCELLED" -> Reason.PROVIDER_CANCELLED;
                default -> fallback;
            };
        }
    }
    private Duration remaining(Job job, Duration limit) {
        Duration ttl = Duration.between(clock.instant(),job.expiresAt()); return ttl.compareTo(limit) < 0 ? ttl : limit;
    }
    private boolean valid(Job job) {
        var player = playback.status();
        return state == State.RUNNING && job.event().sessionId().equals(sessionId)
            && job.expiresAt().isAfter(clock.instant()) && job.epoch() == player.epoch();
    }
    private boolean busy() {
        var p = playback.status();
        return modelJob != null || speechJob != null || comments.size() + templates.size() + synthesis.size() + p.queued() > 0 || p.playing();
    }
    private void cancelModel(Reason reason) {
        if (modelJob != null) { Job job = modelJob; modelJob = null; trace(job,Stage.CANCELLED,reason); cancelPort("MODEL",job,() -> conversation.cancel(job.id())); }
        if (modelCall != null) { modelCall.cancel(true); modelCall = null; }
    }
    private void cancelSpeech(Reason reason) {
        if (speechJob != null) { Job job = speechJob; speechJob = null; trace(job,Stage.CANCELLED,reason); cancelPort("SPEECH",job,() -> speech.cancel(job.id())); }
        if (speechCall != null) { speechCall.cancel(true); speechCall = null; }
    }
    private void cancelPort(String type, Job job, Runnable cancel) {
        try { cancel.run(); }
        catch (RuntimeException ignored) {
            synchronized (this) {
                // Cleanup failures must not abort the rest of stop or overwrite a newer session.
                if (!sessionId.equals(job.event().sessionId()) || terminalError || !job.failed().compareAndSet(false,true)) return;
                failures++; recordError(type+"_CANCEL_FAILED");
            }
        }
    }
    private void recordError(String code) {
        if (!terminalError) { lastError=code; lastErrorAt=clock.instant().toString(); }
    }
    private void recordTerminalError(String code) {
        recordError(code); terminalError=true;
    }
    public synchronized Status status() {
        return new Status(sessionId,state,mode,conversationMode,speechMode,configs.snapshot().version(),appliedVersion,
            roomId,comments.size(),templates.size(),synthesis.size(),modelJob != null,speechJob != null,dropped,failures,playback.status(),lastError,lastErrorAt);
    }
    public synchronized DiagnosticsView diagnostics() {
        var runtime=status(); var snapshot=diagnostics.snapshot(); var waiting=new ArrayList<WaitReason>();
        if (state!=State.RUNNING) waiting.add(WaitReason.STOPPED);
        else {
            if (snapshot.lastReceivedAt()==null) waiting.add(WaitReason.NO_MESSAGES);
            if (!runtime.playback().ready()) waiting.add(WaitReason.PLAYER_NOT_READY);
            if (modelJob!=null || comments.size()>0) waiting.add(WaitReason.MODEL_WAIT);
            if (speechJob!=null || synthesis.size()+templates.size()>0) waiting.add(WaitReason.SPEECH_WAIT);
            if (runtime.playback().queued()>0) waiting.add(WaitReason.PLAYBACK_WAIT);
            if (runtime.playback().playing()) waiting.add(playback.hasStarted() ? WaitReason.PLAYING : WaitReason.PLAYBACK_WAIT);
            if ((modelJob==null && modelWorker.getActiveCount()>0) || (speechJob==null && speechWorker.getActiveCount()>0)) waiting.add(WaitReason.WORKER_BUSY);
            if (waiting.isEmpty()) waiting.add(WaitReason.NO_PENDING_TASKS);
        }
        return new DiagnosticsView(runtime,List.copyOf(waiting),workerStatus(modelWorker),workerStatus(speechWorker),recent.size(),assets.size(),snapshot);
    }
    private static WorkerStatus workerStatus(ThreadPoolExecutor worker) {
        return new WorkerStatus(worker.getPoolSize(),worker.getActiveCount(),worker.getQueue().size(),worker.getCompletedTaskCount());
    }
    private void trace(Job job, Stage stage, Reason reason) { diagnostics.mark(job.event().sessionId(),job.id(),stage,reason); }
    private Reason invalidReason(Job job) {
        if (!job.expiresAt().isAfter(clock.instant())) return Reason.TASK_EXPIRED;
        return state!=State.RUNNING || !sessionId.equals(job.event().sessionId()) ? Reason.SESSION_STOPPED : Reason.PLAYER_CHANGED;
    }
    private void discardInvalid(Job job) { dropped++; trace(job,Stage.DROPPED,invalidReason(job)); }
    private void discarded(Job job, BoundedPriorityQueue.DiscardReason reason) {
        dropped++;
        trace(job,reason==BoundedPriorityQueue.DiscardReason.CLEARED || reason==BoundedPriorityQueue.DiscardReason.INVALIDATED ? Stage.CANCELLED : Stage.DROPPED,
            switch(reason) {
                case EXPIRED -> Reason.TASK_EXPIRED; case FULL -> Reason.QUEUE_FULL;
                case CAPACITY_REDUCED -> Reason.CAPACITY_REDUCED; case INVALIDATED -> Reason.PLAYER_CHANGED;
                case CLEARED -> Reason.SESSION_STOPPED;
            });
    }
    @PreDestroy public synchronized void close() {
        closed = true; stop(sessionId); modelWorker.shutdownNow(); speechWorker.shutdownNow();
    }
    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("bigfish-" + name).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
}
