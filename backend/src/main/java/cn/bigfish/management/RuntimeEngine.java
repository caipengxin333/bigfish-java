package cn.bigfish.management;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
import cn.bigfish.conversation.GiftTemplates;
import cn.bigfish.event.*;
import cn.bigfish.playback.*;
import cn.bigfish.scheduler.BoundedPriorityQueue;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
                       int priority, long epoch, String text) implements BoundedPriorityQueue.Item {
        Job withText(String value) { return new Job(id,event,config,expiresAt,readyAt,priority,epoch,value); }
    }
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
    private long appliedVersion, dropped, failures;
    private final RecentEvents recent = new RecentEvents();
    private final BoundedPriorityQueue<Job> comments = new BoundedPriorityQueue<>(j -> dropped++);
    private final BoundedPriorityQueue<Job> templates = new BoundedPriorityQueue<>(j -> dropped++);
    private final BoundedPriorityQueue<Job> synthesis = new BoundedPriorityQueue<>(j -> dropped++);
    // A blocked port cannot consume unlimited worker threads or accumulate executor tasks.
    private final ThreadPoolExecutor modelWorker = worker("conversation");
    private final ThreadPoolExecutor speechWorker = worker("speech");
    private State state = State.STOPPED;
    private String sessionId = "", roomId = "", conversationMode = "MOCK";
    private ConversationPort conversation;
    private Job modelJob, speechJob;
    private Future<?> modelCall, speechCall;
    private Instant modelUntil, speechUntil, lastWelcome = Instant.MIN;
    private boolean closed;

    public RuntimeEngine(ConfigStore configs, ConversationPort mock, Supplier<ConversationPort> realFactory,
                         SpeechPort speech, PlaybackCoordinator playback, AudioAssets assets, Clock clock,
                         Duration modelTimeout, Duration speechTimeout) {
        this.configs = configs; this.mock = mock; this.realFactory = realFactory; this.speech = speech; this.testSpeech=speech;
        this.playback = playback; this.assets = assets; this.clock = clock;
        this.modelTimeout = modelTimeout; this.speechTimeout = speechTimeout;
        configs.current();
    }
    public synchronized String startMock() { return startMock(false); }
    /** Real calls require both this explicit choice and the server-side credential/paid-call gate. */
    public synchronized String startMock(boolean useDeepSeek) { return startMock(useDeepSeek,testSpeech,"LOCAL_TEST_TONE"); }
    public synchronized String startMock(boolean useDeepSeek, SpeechPort selectedSpeech, String selectedSpeechMode) {
        if (closed || (state != State.STOPPED && state != State.FAILED)) throw new IllegalStateException("SESSION_ACTIVE");
        state = State.STARTING;
        try {
            conversation = useDeepSeek ? realFactory.get() : mock;
            speech=Objects.requireNonNull(selectedSpeech); speechMode=selectedSpeechMode; lastError=""; lastErrorAt="";
            conversationMode = useDeepSeek ? "DEEPSEEK" : "MOCK";
            sessionId = UUID.randomUUID().toString(); roomId = "mock-room";
            appliedVersion = configs.snapshot().version(); lastWelcome = Instant.MIN;
            recent.clear(); playback.begin(sessionId); state = State.RUNNING;
            return sessionId;
        } catch (RuntimeException error) { state = State.FAILED; throw new IllegalStateException("PROVIDER_UNAVAILABLE"); }
    }
    public synchronized void stop(String id) {
        if (!sessionId.equals(id) || state == State.STOPPED) return;
        state = State.STOPPING; // Close ingress before cancelling any asynchronous work.
        cancelModel(); cancelSpeech(); comments.clear(); templates.clear(); synthesis.clear(); recent.clear();
        playback.stop("SESSION_STOPPED");
        if (conversation instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { lastError="MODEL_CLOSE_FAILED"; lastErrorAt=clock.instant().toString(); }
        }
        state = State.STOPPED;
    }
    @Override public synchronized Result accept(LiveEvent input) {
        if (state != State.RUNNING || input == null || !sessionId.equals(input.sessionId())) return Result.REJECTED_SESSION;
        Instant now = clock.instant();
        LiveEvent event;
        try { event = EventValidator.normalize(input, now); }
        catch (IllegalArgumentException e) { return Result.INVALID; }
        if (!roomId.equals(event.roomId())) return Result.INVALID;
        if (recent.contains(event.eventId(), now)) return Result.DUPLICATE;
        ConfigSnapshot config = configs.snapshot();
        var settings = config.settings();
        var player = playback.status();
        if (event.type() == LiveEvent.Type.LIKE) { recent.remember(event.eventId(),now); return Result.ACCEPTED; }
        boolean welcome = event.type() == LiveEvent.Type.FOLLOW || event.type() == LiveEvent.Type.ENTRANCE;
        if (welcome && (busy() || lastWelcome.plusSeconds(settings.welcomeIntervalSeconds()).isAfter(now))) {
            dropped++; recent.remember(event.eventId(),now); return Result.ACCEPTED;
        }
        int priority = switch (event.type()) {
            case COMMENT -> settings.priorities().comment(); case GIFT -> settings.priorities().gift();
            case FOLLOW -> settings.priorities().follow(); default -> settings.priorities().entrance();
        };
        long ttl = event.type() == LiveEvent.Type.COMMENT ? 60 : event.type() == LiveEvent.Type.GIFT ? 120 : 40;
        Job job = new Job(UUID.randomUUID().toString(),event,config,now.plusSeconds(ttl),
            event.type() == LiveEvent.Type.GIFT ? now.plusMillis(150) : now,priority,player.epoch(),null);
        boolean accepted;
        if (event.type() == LiveEvent.Type.GIFT && mergeGift(job, now)) accepted = true;
        else accepted = (event.type() == LiveEvent.Type.COMMENT ? comments : templates)
            .offer(job,settings.synthesisQueueCapacity(),now);
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
            previous.priority(),previous.epoch(),null));
        return true;
    }
    @Scheduled(fixedDelay = 25)
    public synchronized void tick() {
        try { advance(); }
        catch (RuntimeException error) {
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
        if (modelJob != null && (!valid(modelJob) || !modelUntil.isAfter(now))) { failed("MODEL",modelJob,new TimeoutException()); cancelModel(); }
        if (speechJob != null && (!valid(speechJob) || !speechUntil.isAfter(now))) { failed("SPEECH",speechJob,new TimeoutException()); cancelSpeech(); }
        // Do not spend model/TTS work when there is no ready player. On disconnect, purge epoch-bound work.
        comments.discardWhere(j -> j.epoch() != player.epoch());
        templates.discardWhere(j -> j.epoch() != player.epoch());
        synthesis.discardWhere(j -> j.epoch() != player.epoch());
        if (!player.ready()) return;
        Job template;
        while ((template = templates.poll(now)) != null) {
            if (valid(template)) synthesis.offer(template.withText(GiftTemplates.render(template.event())),capacity,now);
            else dropped++;
        }
        if (modelJob == null && (modelJob = nextValid(comments,now)) != null) startModel(modelJob);
        if (speechJob == null && (speechJob = nextValid(synthesis,now)) != null) startSpeech(speechJob);
    }
    private Job nextValid(BoundedPriorityQueue<Job> queue, Instant now) {
        Job job;
        while ((job = queue.poll(now)) != null) { if (valid(job)) return job; dropped++; }
        return null;
    }
    private void startModel(Job job) {
        Duration deadline = remaining(job,modelTimeout); modelUntil = clock.instant().plus(deadline);
        ConversationPort port = conversation;
        try {
            modelCall = modelWorker.submit(() -> {
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
                        if (modelJob == job && valid(job) && !value.isBlank()) {
                            if (job.config().settings().readComment()) value = job.event().payload().get("content") + "。" + value;
                            synthesis.offer(job.withText(value),configs.snapshot().settings().synthesisQueueCapacity(),clock.instant());
                        }
                    }
                } catch (Exception error) { port.cancel(job.id()); failed("MODEL",job,error); }
                finally { synchronized (this) { if (modelJob == job) { modelJob = null; modelCall = null; } } }
            });
        } catch (RejectedExecutionException error) { modelJob = null; dropped++; }
    }
    private void startSpeech(Job job) {
        Duration deadline = remaining(job,"ALIYUN_NLS".equals(speechMode) ? Duration.ofSeconds(10) : speechTimeout); speechUntil = clock.instant().plus(deadline);
        SpeechPort port=speech;
        try {
            speechCall = speechWorker.submit(() -> {
                SpeechPort.AudioAsset asset=null;
                try {
                    asset = port.synthesize(job.id(),job.text(),job.config().settings().voice(),deadline)
                        .toCompletableFuture().get(deadline.toMillis(),TimeUnit.MILLISECONDS);
                    if(asset.duration()==null || asset.duration().isNegative() || asset.duration().isZero()
                        || asset.duration().compareTo(Duration.ofSeconds(25))>0)
                        throw new cn.bigfish.speech.SpeechFailure(cn.bigfish.speech.SpeechFailure.Code.AUDIO_TOO_LONG);
                    var content = assets.load(asset);
                    synchronized (this) {
                        if (speechJob == job && valid(job)) {
                            var task = new PlaybackTask(job.id(),job.event().sessionId(),job.config().version(),job.event().type().name(),
                                job.text(),"/api/v1/audio/" + job.id(),job.priority(),job.expiresAt());
                            if (!playback.enqueue(task,content,configs.snapshot().settings().playbackQueueCapacity(),job.epoch(),asset.duration())) dropped++;
                        }
                    }
                } catch (Exception error) { port.cancel(job.id()); failed("SPEECH",job,error); }
                finally {
                    try { if(asset!=null) port.release(asset); }
                    catch(RuntimeException error) { failed("SPEECH",job,error); }
                    synchronized (this) { if (speechJob == job) { speechJob = null; speechCall = null; } }
                }
            });
        } catch (RejectedExecutionException error) { speechJob = null; dropped++; }
    }
    private synchronized void failed(String type, Job job, Exception error) {
        if (!valid(job)) return;
        failures++;
        lastError=type+"_FAILED"; lastErrorAt=clock.instant().toString();
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof cn.bigfish.conversation.DeepSeekFailure known) { lastError="MODEL_"+known.diagnostic().code().name(); break; }
            if(cause instanceof cn.bigfish.speech.SpeechFailure known) { lastError="NLS_"+known.code().name(); break; }
            if(cause instanceof TimeoutException) lastError=type+"_OVERALL_TIMEOUT";
        }
        log.warn("task_failure type={} errorType={} taskId={} elapsedMs={}",type,error.getClass().getSimpleName(),
            job.id(),Duration.between(job.event().receivedAt(),clock.instant()).toMillis());
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
    private void cancelModel() {
        if (modelJob != null) { Job job = modelJob; modelJob = null; conversation.cancel(job.id()); }
        if (modelCall != null) { modelCall.cancel(true); modelCall = null; }
    }
    private void cancelSpeech() {
        if (speechJob != null) { Job job = speechJob; speechJob = null; speech.cancel(job.id()); }
        if (speechCall != null) { speechCall.cancel(true); speechCall = null; }
    }
    public synchronized Status status() {
        return new Status(sessionId,state,"MOCK",conversationMode,speechMode,configs.snapshot().version(),appliedVersion,
            roomId,comments.size(),templates.size(),synthesis.size(),modelJob != null,speechJob != null,dropped,failures,playback.status(),lastError,lastErrorAt);
    }
    @PreDestroy public synchronized void close() {
        closed = true; stop(sessionId); modelWorker.shutdownNow(); speechWorker.shutdownNow();
    }
    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("bigfish-" + name).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
}
