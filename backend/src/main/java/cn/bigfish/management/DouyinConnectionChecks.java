package cn.bigfish.management;

import cn.bigfish.collector.*;
import cn.bigfish.configuration.*;
import cn.bigfish.configuration.DouyinCredentialSettings.*;
import cn.bigfish.contract.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.scheduling.annotation.Scheduled;

/** Internal bounded receive check. No engine, model or speech dependency; no public routes. */
public final class DouyinConnectionChecks implements AutoCloseable {
    public enum State { CONNECTING, OBSERVING, STOPPING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }
    public record View(String checkId,long configVersion,String configRevision,Source source,Mode mode,
                       String localValidation,State state,String reason,String cleanupError,
                       boolean connected,long comments,long gifts,Instant startedAt,Instant deadline,boolean resourcesReleased) {}
    private static final Set<String> SAFE_ERRORS;
    static {
        var codes=new HashSet<String>();
        for(var code:DouyinFailure.Code.values()) codes.add(code.name());
        for(var code:CollectorDiagnostics.Counter.values()) codes.add(code.name());
        SAFE_ERRORS=Set.copyOf(codes);
    }
    private static final class Check {
        final String id=UUID.randomUUID().toString();
        final Lease lease;
        final SessionCollectors.Handle handle;
        final String validation;
        final Instant started,deadline;
        final long deadlineNanos;
        volatile Thread starter;
        volatile boolean startDone,stopDone;
        volatile String startFailure="",cleanupError="";
        boolean open=true,stopSubmitted,connected;
        long comments,gifts;
        State terminal;
        String reason="";
        Check(Lease lease,SessionCollectors.Handle handle,Clock clock,Duration duration) {
            this.lease=lease; this.handle=handle; validation=lease.snapshot().validation(clock);
            started=clock.instant(); deadline=started.plus(duration); deadlineNanos=System.nanoTime()+duration.toNanos();
        }
    }
    private final DouyinCredentialSettings settings;
    private final Function<Snapshot,SessionCollectors.Handle> factory;
    private final Clock clock;
    // No queued operations; at most one starter and one cleanup worker even for a noncompliant adapter.
    private final ThreadPoolExecutor starter=worker("bigfish-collector-check-start"),stopper=worker("bigfish-collector-check-stop");
    private Check current;
    private View last;
    private Instant lastStarted;
    private final ArrayDeque<Instant> attempts=new ArrayDeque<>(); // At most three timestamps, no history table.
    private boolean closed;
    public DouyinConnectionChecks(DouyinCredentialSettings settings,Function<Snapshot,SessionCollectors.Handle> factory,Clock clock) {
        this.settings=settings; this.factory=factory; this.clock=clock;
    }
    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name(name).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized View start(String expectedRevision,String room,Duration duration) {
        tick();
        if(closed) throw new Failure("CHECK_SERVICE_CLOSED",503);
        if(current!=null) throw new Failure(current.terminal==null ? "CHECK_ACTIVE" : "CHECK_STOPPING",409);
        if(duration==null || duration.compareTo(Duration.ofSeconds(1))<0 || duration.compareTo(Duration.ofSeconds(30))>0)
            throw new Failure("CHECK_DURATION_INVALID",400);
        try { room=DouyinRoomInput.parse(room).webRoomId(); }
        catch(DouyinFailure | RuntimeException invalid) { throw new Failure("CHECK_ROOM_INVALID",400); }
        if(expectedRevision==null) throw new Failure("CREDENTIAL_CONFIG_CONFLICT",409);
        while(!attempts.isEmpty() && !attempts.peekFirst().isAfter(clock.instant().minus(Duration.ofMinutes(5)))) attempts.removeFirst();
        if(attempts.size()>=3) throw new Failure("CHECK_ATTEMPT_LIMIT",429);
        if(lastStarted!=null && clock.instant().isBefore(lastStarted.plusSeconds(30))) throw new Failure("CHECK_COOLDOWN",429);
        Lease lease=settings.acquire(Use.CHECK,expectedRevision);
        SessionCollectors.Handle handle;
        try {
            lease.snapshot().requireConnection(clock);
            handle=Objects.requireNonNull(factory.apply(lease.snapshot()));
        } catch(RuntimeException failure) {
            lease.close();
            if(failure instanceof Failure known) throw known;
            if(failure instanceof SessionCollectors.ConfigurationFailure known) throw new Failure(known.code(),503);
            throw new Failure("CHECK_START_FAILED",503);
        }
        Check check=new Check(lease,handle,clock,duration); current=check;
        lastStarted=clock.instant(); attempts.addLast(lastStarted); String requestedRoom=room;
        try {
            starter.execute(() -> {
                check.starter=Thread.currentThread();
                try {
                    // Cleanup is submitted only after this start returns, so stop cannot race ahead of start.
                    if(isOpen(check)) check.handle.port().start(requestedRoom,check.id,event -> received(check,event));
                } catch(RuntimeException failure) { check.startFailure="CHECK_START_FAILED"; }
                finally { check.starter=null; check.startDone=true; tick(); }
            });
        } catch(RejectedExecutionException unavailable) { check.startFailure="CHECK_WORKER_BUSY"; check.startDone=true; }
        return view(check,false);
    }
    private synchronized boolean isOpen(Check check) { return current==check && check.open; }
    private synchronized void received(Check check,LiveEvent event) {
        if(current!=check || !check.open || event==null || !check.id.equals(event.sessionId())) return;
        if(event.type()==LiveEvent.Type.COMMENT) check.comments=saturatedIncrement(check.comments);
        if(event.type()==LiveEvent.Type.GIFT) check.gifts=saturatedIncrement(check.gifts);
    }
    private static long saturatedIncrement(long value) { return value==Long.MAX_VALUE ? value : value+1; }
    public synchronized View status(String id) {
        tick();
        if(current!=null && current.id.equals(id)) return view(current,false);
        if(last!=null && last.checkId().equals(id)) return last;
        throw new Failure("CHECK_NOT_FOUND",404);
    }
    public synchronized View cancel(String id) {
        if(current==null || !current.id.equals(id)) return status(id); // Old ID cannot cancel a new run.
        finish(current,State.CANCELLED,"USER_CANCELLED"); tick(); return status(id);
    }
    @Scheduled(fixedDelay=50)
    public synchronized void tick() {
        Check check=current;
        if(check==null) { if(closed) { starter.shutdown(); stopper.shutdown(); } return; }
        if(check.terminal==null) {
            try {
                var state=check.handle.port().state();
                check.connected|=state==CollectorPort.State.CONNECTED || check.handle.diagnostics().count(CollectorDiagnostics.Counter.CONNECTED)>0;
                if(!check.startFailure.isEmpty()) finish(check,State.FAILED,check.startFailure);
                else if(state==CollectorPort.State.FAILED || state==CollectorPort.State.RECONNECTING) {
                    String error=check.handle.diagnostics().lastError();
                    finish(check,State.FAILED,SAFE_ERRORS.contains(error) ? error : "CHECK_FAILED");
                } else if(state==CollectorPort.State.ENDED) finish(check,State.COMPLETED,"ROOM_ENDED");
                else if(check.lease.validation().equals("CREDENTIALS_EXPIRED")) finish(check,State.FAILED,"CREDENTIALS_EXPIRED");
                else if(!check.deadline.isAfter(clock.instant()) || System.nanoTime()>=check.deadlineNanos)
                    finish(check,check.connected ? State.COMPLETED : State.TIMED_OUT,check.connected ? "OBSERVATION_COMPLETE" : "CHECK_TIMEOUT");
            } catch(RuntimeException failure) { finish(check,State.FAILED,"CHECK_FAILED"); }
        }
        if(check.terminal==null) return;
        if(check.starter!=null) check.starter.interrupt();
        if(check.startDone && !check.stopSubmitted) {
            try {
                stopper.execute(() -> {
                    try { check.handle.port().stop(); }
                    catch(RuntimeException failure) { check.cleanupError="CHECK_STOP_FAILED"; }
                    finally { check.stopDone=true; tick(); }
                });
                check.stopSubmitted=true;
            } catch(RejectedExecutionException busy) { check.cleanupError="CHECK_CLEANUP_BUSY"; }
        }
        if(check.startDone && check.stopDone) {
            try {
                if(check.handle.released().getAsBoolean()) {
                    last=view(check,true); current=null; check.lease.close();
                    if(closed) { starter.shutdown(); stopper.shutdown(); }
                }
            } catch(RuntimeException failed) { check.cleanupError="CHECK_RESOURCE_STATE_FAILED"; }
        }
    }
    private void finish(Check check,State terminal,String reason) {
        if(check.terminal!=null) return;
        check.open=false; check.terminal=terminal; check.reason=reason; check.lease.stopping();
    }
    private View view(Check check,boolean released) {
        Snapshot snapshot=check.lease.snapshot();
        State state=released ? check.terminal : check.terminal!=null ? State.STOPPING : check.connected ? State.OBSERVING : State.CONNECTING;
        return new View(check.id,snapshot.version(),snapshot.revision(),snapshot.source(),snapshot.mode(),check.validation,state,
            check.reason,check.cleanupError,check.connected,check.comments,check.gifts,check.started,check.deadline,released);
    }
    @PreDestroy @Override public void close() {
        synchronized(this) { closed=true; if(current!=null) finish(current,State.CANCELLED,"SERVICE_CLOSED"); }
        long until=System.nanoTime()+500_000_000L;
        while(System.nanoTime()<until) {
            tick(); synchronized(this) { if(current==null) break; }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        starter.shutdownNow();
        synchronized(this) { if(current==null) stopper.shutdownNow(); }
        // Keep the single cleanup executor available for a late, interrupted start to return and stop.
        // Unreleased resources retain the lease; never claim release merely because shutdown was requested.
    }
}
