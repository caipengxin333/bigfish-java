package cn.bigfish.core;

import cn.bigfish.configuration.DouyinCredentialSettings;
import cn.bigfish.configuration.DouyinCredentialSettings.*;
import cn.bigfish.contract.CollectorPort;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static cn.bigfish.core.DouyinSessionTest.MOCK;
import static org.assertj.core.api.Assertions.*;

class RuntimeCredentialSessionTest {
    @TempDir Path directory;
    final DouyinSessionTest base=new DouyinSessionTest();
    @BeforeEach void setup() { base.directory=directory; }
    static class Versions extends DouyinSessionTest.Factory {
        Versions() { super(); }
        Versions(java.time.Clock clock) { super(clock); }
        final List<Snapshot> versions=new ArrayList<>();
        @Override public Handle douyin(Predicate<String> bind) { versions.add(settings().forCollector()); return super.douyin(bind); }
    }
    @Test void newInteractionSeesNewVersionWhileRunningAndRetiringSourceBlockChanges() throws Exception {
        var factory=new Versions();
        try(var h=base.new Harness(factory)) {
            var settings=factory.settings(); settings.replace(settings.status().revision(),true,"synthetic-cookie","agent","9001",Duration.ofHours(1),null);
            var first=h.start(); var old=factory.current;
            assertThat(settings.status().activeVersion()).isEqualTo(1);
            assertThatThrownBy(() -> settings.guest(settings.status().revision())).hasMessage("SESSION_ACTIVE");
            old.state=CollectorPort.State.RECONNECTING; h.sessions.reconcile();
            assertThat(factory.versions.getFirst().version()).isEqualTo(1);
            old.held=true; h.sessions.stop(first.sessionId());
            assertThatThrownBy(() -> settings.clear(settings.status().revision(),true)).hasMessage("COLLECTOR_STOPPING");
            old.held=false; h.sessions.reconcile(); settings.clear(settings.status().revision(),true);
            var second=h.start(); assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
            assertThat(factory.versions.getLast().version()).isEqualTo(2);
            assertThat(factory.versions.getLast().mode()).isEqualTo(Mode.GUEST);
            old.sink.accept(h.event("old",first.sessionId(),"901",cn.bigfish.contract.LiveEvent.Type.GIFT));
            assertThat(h.sessions.diagnostics().trace().history()).isEmpty();
            assertThat(settings.status().activeVersion()).isEqualTo(2);
        }
    }
    @Test void mockAlsoBlocksUpdatesAndFailedStartReleasesOnlyAfterCollectorExits() throws Exception {
        try(var h=base.new Harness(new Versions())) {
            var settings=h.factory.settings(); var mock=h.sessions.start(MOCK);
            assertThatThrownBy(() -> settings.guest(settings.status().revision())).hasMessage("SESSION_ACTIVE");
            h.sessions.stop(mock.sessionId()); settings.guest(settings.status().revision());
            h.factory.throwOnStart=true; assertThatThrownBy(h::start).hasMessage("COLLECTOR_START_FAILED");
            assertThat(settings.status().activeUse()).isEqualTo("NONE");
            settings.clear(settings.status().revision(),true);
        }
    }
    @Test void localDeadlineStopsExternalInteractionButDoesNotExpireMockOrRenewCredentials() throws Exception {
        var clock=new CoreRig.MutableClock(); var factory=new Versions(clock);
        try(var h=base.new Harness(factory)) {
            var settings=factory.settings();
            settings.replace(settings.status().revision(),true,"fixture","agent","9001",Duration.ofMinutes(1),null);
            var first=h.start(); clock.advance(Duration.ofSeconds(61)); h.sessions.reconcile();
            assertThat(h.sessions.status().state()).isEqualTo("FAILED");
            assertThat(h.sessions.status().collector().lastError()).isEqualTo("CREDENTIALS_EXPIRED");
            assertThat(h.sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThat(h.factory.current.stopped).isTrue(); assertThat(settings.status().validation()).isEqualTo("CREDENTIALS_EXPIRED");
            assertThat(settings.status().activeUse()).isEqualTo("NONE");
            h.sessions.start(MOCK); h.sessions.reconcile(); assertThat(h.sessions.status().state()).isEqualTo("RUNNING");
            assertThat(h.sessions.status().sessionId()).isNotEqualTo(first.sessionId());
        }
    }
    @Test void deadlineAfterEngineTerminationCannotInventNewCollectorRootCause() throws Exception {
        var clock=new CoreRig.MutableClock(); var factory=new Versions(clock);
        try(var h=base.new Harness(factory)) {
            var settings=factory.settings();
            settings.replace(settings.status().revision(),true,"fixture","agent","9001",Duration.ofMinutes(1),null);
            var view=h.start(); h.rig.engine.collectorFailed(view.sessionId());
            clock.advance(Duration.ofSeconds(61)); h.sessions.reconcile();
            assertThat(h.sessions.status().state()).isEqualTo("FAILED");
            assertThat(h.sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThat(h.sessions.status().collector().state()).isEqualTo("STOPPED");
            assertThat(h.sessions.status().collector().lastError()).isEmpty();
            assertThat(settings.status().activeUse()).isEqualTo("NONE");
        }
    }
}
