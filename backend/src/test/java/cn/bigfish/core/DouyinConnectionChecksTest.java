package cn.bigfish.core;

import cn.bigfish.collector.*;
import cn.bigfish.configuration.*;
import cn.bigfish.configuration.DouyinCredentialSettings.*;
import cn.bigfish.contract.*;
import cn.bigfish.management.DouyinConnectionChecks;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DouyinConnectionChecksTest {
    final CoreRig.MutableClock clock=new CoreRig.MutableClock();
    final DouyinCredentialSettings settings=new DouyinCredentialSettings(key -> null,clock);
    static class Source implements CollectorPort {
        volatile State state=State.STOPPED;
        volatile boolean stopped,held,startFailure;
        volatile int starts,stops;
        volatile String id;
        volatile Consumer<LiveEvent> sink;
        CountDownLatch stopGate;
        public void start(String room,String id,Consumer<LiveEvent> sink) {
            starts++; this.id=id; this.sink=sink;
            if(startFailure) throw new IllegalStateException("private-cookie-body");
            state=State.CONNECTING;
        }
        public void stop() {
            stops++;
            if(stopGate!=null) try { if(!stopGate.await(2,TimeUnit.SECONDS)) throw new AssertionError("stop gate timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            state=State.STOPPED; stopped=true;
        }
        public State state() { return state; }
        SessionCollectors.Handle handle() { return new SessionCollectors.Handle(this,new CollectorDiagnostics(),() -> stopped && !held); }
        void emit(String session,LiveEvent.Type type) { sink.accept(new LiveEvent(1,"private-id",session,"123",type,"private-user","private-name",Instant.EPOCH,Map.of())); }
    }
    static void await(DouyinConnectionChecks checks,BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+2_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<until) { checks.tick(); Thread.sleep(2); }
        assertThat(condition.getAsBoolean()).isTrue();
    }
    String start(DouyinConnectionChecks checks) { return checks.start(settings.status().revision(),"123456",Duration.ofSeconds(2)).checkId(); }
    @Test void separatesLocalValidationConnectionCommentsAndGiftsAndIgnoresLateCallbacks() throws Exception {
        var source=new Source();
        try(var checks=new DouyinConnectionChecks(settings,s -> source.handle(),clock)) {
            String id=start(checks); await(checks,() -> source.starts==1);
            assertThat(checks.status(id).localValidation()).isEqualTo("GUEST_AUTO");
            assertThat(checks.status(id).connected()).isFalse();
            source.state=CollectorPort.State.CONNECTED; checks.tick();
            assertThat(checks.status(id).connected()).isTrue(); assertThat(checks.status(id).comments()).isZero();
            source.emit(id,LiveEvent.Type.COMMENT); assertThat(checks.status(id).comments()).isEqualTo(1); assertThat(checks.status(id).gifts()).isZero();
            source.emit(UUID.randomUUID().toString(),LiveEvent.Type.GIFT); source.emit(id,LiveEvent.Type.GIFT);
            clock.advance(Duration.ofSeconds(3)); checks.tick();
            await(checks,() -> checks.status(id).resourcesReleased()); var complete=checks.status(id);
            assertThat(complete.state()).isEqualTo(DouyinConnectionChecks.State.COMPLETED);
            assertThat(complete.gifts()).isEqualTo(1); assertThat(source.stops).isEqualTo(1);
            source.emit(id,LiveEvent.Type.COMMENT); assertThat(checks.status(id)).isEqualTo(complete);
            assertThat(complete.toString()).doesNotContain("private-"); assertThat(settings.status().activeUse()).isEqualTo("NONE");
        }
    }
    @Test void cancellationIsImmediateButUnreleasedResourcesKeepMutationAndInteractionGate() throws Exception {
        var source=new Source(); source.held=true; source.stopGate=new CountDownLatch(1);
        try(var checks=new DouyinConnectionChecks(settings,s -> source.handle(),clock)) {
            String id=start(checks); await(checks,() -> source.starts==1);
            assertThatThrownBy(() -> settings.acquire(Use.INTERACTION)).hasMessage("CHECK_ACTIVE");
            long at=System.nanoTime(); checks.cancel(id);
            assertThat(System.nanoTime()-at).isLessThan(TimeUnit.MILLISECONDS.toNanos(250));
            assertThat(checks.cancel(id).state()).isEqualTo(DouyinConnectionChecks.State.STOPPING);
            assertThatThrownBy(() -> settings.clear(settings.status().revision(),true)).hasMessage("CHECK_STOPPING");
            source.stopGate.countDown(); await(checks,() -> source.stopped);
            assertThat(checks.status(id).resourcesReleased()).isFalse();
            source.held=false; checks.tick(); assertThat(checks.status(id).state()).isEqualTo(DouyinConnectionChecks.State.CANCELLED);
            assertThat(checks.status(id).reason()).isEqualTo("USER_CANCELLED"); assertThat(source.stops).isEqualTo(1);
            try(var lease=settings.acquire(Use.INTERACTION)) { assertThat(lease.snapshot().mode()).isEqualTo(Mode.GUEST); }
        } finally { source.stopGate.countDown(); }
    }
    @Test void timeoutStartFailureAndObservationLimitsReleaseResourcesWithoutRetries() throws Exception {
        var sources=new ArrayList<Source>();
        try(var checks=new DouyinConnectionChecks(settings,s -> { var value=new Source(); sources.add(value); return value.handle(); },clock)) {
            String first=start(checks); await(checks,() -> sources.getFirst().starts==1);
            clock.advance(Duration.ofSeconds(3)); await(checks,() -> checks.status(first).resourcesReleased());
            assertThat(checks.status(first).state()).isEqualTo(DouyinConnectionChecks.State.TIMED_OUT);
            assertThatThrownBy(() -> start(checks)).hasMessage("CHECK_COOLDOWN");
            clock.advance(Duration.ofSeconds(30)); String second=start(checks); await(checks,() -> sources.getLast().starts==1);
            assertThat(checks.cancel(first).checkId()).isEqualTo(first); assertThat(checks.status(second).state()).isEqualTo(DouyinConnectionChecks.State.CONNECTING);
            sources.getFirst().emit(first,LiveEvent.Type.GIFT); assertThat(checks.status(second).gifts()).isZero();
            checks.cancel(second); await(checks,() -> checks.status(second).resourcesReleased());
            clock.advance(Duration.ofSeconds(30)); String third=start(checks); checks.cancel(third); await(checks,() -> checks.status(third).resourcesReleased());
            clock.advance(Duration.ofSeconds(30)); assertThatThrownBy(() -> start(checks)).hasMessage("CHECK_ATTEMPT_LIMIT");
            assertThat(sources).hasSize(3); assertThat(sources).allMatch(s -> s.stops==1);
            clock.advance(Duration.ofMinutes(5)); String afterCooldown=start(checks);
            checks.cancel(afterCooldown); await(checks,() -> checks.status(afterCooldown).resourcesReleased());
            assertThat(sources).hasSize(4);
        }
        var failing=new Source(); failing.startFailure=true;
        try(var checks=new DouyinConnectionChecks(settings,s -> failing.handle(),clock)) {
            String id=start(checks); await(checks,() -> checks.status(id).resourcesReleased());
            assertThat(checks.status(id).state()).isEqualTo(DouyinConnectionChecks.State.FAILED);
            assertThat(checks.status(id).reason()).isEqualTo("CHECK_START_FAILED"); assertThat(checks.status(id).toString()).doesNotContain("private-");
        }
    }
    @Test void activeInteractionBadRevisionAndExpiredIdentityNeverAllocateCheck() {
        try(var checks=new DouyinConnectionChecks(settings,s -> { throw new AssertionError("must not allocate"); },clock)) {
            try(var lease=settings.acquire(Use.INTERACTION)) { assertThatThrownBy(() -> start(checks)).hasMessage("SESSION_ACTIVE"); }
            assertThatThrownBy(() -> checks.start("stale","123",Duration.ofSeconds(1))).hasMessage("CREDENTIAL_CONFIG_CONFLICT");
            assertThatThrownBy(() -> checks.start(settings.status().revision(),"123",Duration.ofSeconds(31))).hasMessage("CHECK_DURATION_INVALID");
            settings.replace(settings.status().revision(),true,"fixture","agent",null,Duration.ofHours(1),null);
            clock.advance(Duration.ofHours(2));
            assertThatThrownBy(() -> start(checks)).hasMessage("CREDENTIALS_EXPIRED");
            assertThat(settings.status().activeUse()).isEqualTo("NONE");
        }
    }
    @Test void actualFactoryBoundsProviderAttemptsAndIncludesItsResourcesInCleanup() throws Exception {
        var factory=new CollectorCredentialsTest.Factory(key -> null,clock);
        when(factory.provider.resolve(anyString(),nullable(String.class))).thenThrow(new DouyinFailure(DouyinFailure.Code.HTTP_UNAVAILABLE));
        var handle=factory.check(factory.settings().current());
        try {
            handle.port().start("123456",UUID.randomUUID().toString(),event -> { throw new AssertionError(); });
            long until=System.nanoTime()+3_000_000_000L;
            while(handle.port().state()!=CollectorPort.State.FAILED && System.nanoTime()<until) Thread.sleep(5);
            assertThat(handle.port().state()).isEqualTo(CollectorPort.State.FAILED);
            assertThat(handle.diagnostics().lastError()).isEqualTo("ATTEMPT_LIMIT");
            verify(factory.provider,times(1)).resolve(anyString(),nullable(String.class));
        } finally { handle.port().stop(); }
        long until=System.nanoTime()+2_000_000_000L;
        while(!handle.released().getAsBoolean() && System.nanoTime()<until) Thread.sleep(5);
        assertThat(handle.released().getAsBoolean()).isTrue();
    }
    @Test void cancellationDuringNoncompliantStartCannotStopBeforeStartOrAllocateAnotherRun() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var source=new Source() {
            @Override public void start(String room,String id,Consumer<LiveEvent> sink) {
                entered.countDown(); boolean done=false;
                while(!done) try { done=release.await(2,TimeUnit.SECONDS); if(!done) throw new AssertionError("start gate timeout"); }
                    catch(InterruptedException ignored) { }
                super.start(room,id,sink);
            }
        };
        try(var checks=new DouyinConnectionChecks(settings,s -> source.handle(),clock)) {
            String id=start(checks); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            checks.cancel(id); clock.advance(Duration.ofSeconds(31)); checks.tick();
            assertThat(source.stops).isZero(); assertThat(checks.status(id).resourcesReleased()).isFalse();
            assertThatThrownBy(() -> start(checks)).hasMessage("CHECK_STOPPING");
            release.countDown(); await(checks,() -> checks.status(id).resourcesReleased());
            assertThat(source.starts).isEqualTo(1); assertThat(source.stops).isEqualTo(1);
        } finally { release.countDown(); }
    }
    @Test void serviceCloseStillCleansUpLateStarterAndLocalExpiryStopsCheck() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var source=new Source() {
            @Override public void start(String room,String id,Consumer<LiveEvent> sink) {
                entered.countDown(); boolean done=false;
                while(!done) try { done=release.await(2,TimeUnit.SECONDS); if(!done) throw new AssertionError("late start timeout"); }
                    catch(InterruptedException ignored) { }
                super.start(room,id,sink);
            }
        };
        var checks=new DouyinConnectionChecks(settings,s -> source.handle(),clock);
        try {
            String id=start(checks); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); checks.close();
            release.countDown(); await(checks,() -> checks.status(id).resourcesReleased());
            assertThat(source.stops).isEqualTo(1); assertThat(checks.status(id).reason()).isEqualTo("SERVICE_CLOSED");
            assertThatThrownBy(() -> start(checks)).hasMessage("CHECK_SERVICE_CLOSED");
        } finally { release.countDown(); checks.close(); }
        settings.replace(settings.status().revision(),true,"fixture","agent","9001",Duration.ofMinutes(1),null);
        clock.advance(Duration.ofSeconds(59)); var fresh=new Source();
        try(var check=new DouyinConnectionChecks(settings,s -> fresh.handle(),clock)) {
            String id=start(check); await(check,() -> fresh.starts==1);
            fresh.state=CollectorPort.State.CONNECTED; clock.advance(Duration.ofSeconds(1)); check.tick();
            await(check,() -> check.status(id).resourcesReleased());
            assertThat(check.status(id).connected()).isTrue(); assertThat(check.status(id).reason()).isEqualTo("CREDENTIALS_EXPIRED");
        }
    }
}
