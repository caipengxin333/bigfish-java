package cn.bigfish.core;

import cn.bigfish.configuration.*;
import cn.bigfish.configuration.DouyinCredentialSettings.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeCredentialSettingsTest {
    final CoreRig.MutableClock clock=new CoreRig.MutableClock();
    DouyinCredentialSettings empty() { return new DouyinCredentialSettings(key -> null,clock); }
    View replace(DouyinCredentialSettings store,String visitor) {
        return store.replace(store.status().revision(),true,"synthetic-private-cookie=one","synthetic-private-UA",visitor,Duration.ofHours(1),null);
    }
    @Test void runtimeOverridesEnvironmentUntilRestartAndExplicitGuestAndClearNeverFallBack() {
        var env=CollectorCredentialsTest.external(clock); var reads=new AtomicInteger();
        var store=new DouyinCredentialSettings(key -> { reads.incrementAndGet(); return env.get(key); },clock);
        assertThat(store.status().source()).isEqualTo(Source.ENVIRONMENT); int initialReads=reads.get();
        String original=store.status().revision(); replace(store,"90000002");
        assertThat(store.status().source()).isEqualTo(Source.RUNTIME); assertThat(store.status().version()).isEqualTo(1);
        store.guest(store.status().revision());
        assertThat(store.status().mode()).isEqualTo(Mode.GUEST); assertThat(store.status().cookieConfigured()).isFalse();
        replace(store,"90000003"); store.clear(store.status().revision(),true);
        for(int i=0;i<10;i++) {
            assertThat(store.status().mode()).isEqualTo(Mode.GUEST); assertThat(store.status().source()).isEqualTo(Source.RUNTIME);
            assertThat(store.status().localExpiresAt()).isNull();
        }
        assertThat(reads).hasValue(initialReads);
        var restarted=new DouyinCredentialSettings(env::get,clock);
        assertThat(restarted.status().source()).isEqualTo(Source.ENVIRONMENT);
        assertThat(restarted.status().mode()).isEqualTo(Mode.EXTERNAL); assertThat(restarted.status().version()).isZero();
        assertThat(restarted.status().revision()).isNotEqualTo(original).isNotEqualTo(store.status().revision());
        assertThatThrownBy(() -> restarted.clear(original,true)).hasMessage("CREDENTIAL_CONFIG_CONFLICT");
        assertThat(empty().status().source()).isEqualTo(Source.DEFAULT);
    }
    @Test void emptyInputDoesNotClearAndReplacementAndClearRequireExplicitIntent() {
        var store=empty(); replace(store,"9001"); var before=store.status();
        for(String cookie:new String[]{null,"","  "})
            assertThatThrownBy(() -> store.replace(before.revision(),true,cookie,"agent","9001",Duration.ofHours(1),null)).hasMessage("CREDENTIALS_MISSING");
        assertThatThrownBy(() -> store.replace(before.revision(),false,"fixture","agent","9001",Duration.ofHours(1),null)).hasMessage("REPLACEMENT_REQUIRED");
        assertThatThrownBy(() -> store.clear(before.revision(),false)).hasMessage("CLEAR_CONFIRMATION_REQUIRED");
        assertThat(store.status()).isEqualTo(before);
        store.guest(before.revision()); assertThatThrownBy(() -> store.clear(before.revision(),true)).hasMessage("CREDENTIAL_CONFIG_CONFLICT");
    }
    @Test void deadlineIsExplicitBoundedAndNeverExtendedByQueries() {
        var store=empty(); String revision=store.status().revision();
        for(Duration duration:List.of(Duration.ZERO,Duration.ofSeconds(59),Duration.ofDays(8)))
            assertThatThrownBy(() -> store.replace(revision,true,"fixture","agent","9001",duration,null)).hasMessage("LOCAL_DEADLINE_INVALID");
        assertThatThrownBy(() -> store.replace(revision,true,"fixture","agent","9001",null,null)).hasMessage("LOCAL_DEADLINE_INVALID");
        assertThatThrownBy(() -> store.replace(revision,true,"fixture","agent","9001",Duration.ofHours(1),clock.instant().plusSeconds(90))).hasMessage("LOCAL_DEADLINE_INVALID");
        Instant until=clock.instant().plusSeconds(60);
        store.replace(revision,true,"fixture","agent","9001",null,until);
        clock.advance(Duration.ofSeconds(60));
        assertThat(store.status().validation()).isEqualTo("CREDENTIALS_EXPIRED");
        assertThat(store.status().localExpiresAt()).isEqualTo(until);
        assertThatThrownBy(() -> store.current().requireConnection(clock)).hasMessage("CREDENTIALS_EXPIRED");
        assertThat(store.status().version()).isEqualTo(1);
    }
    @Test void autoIdentityUsesApprovedCollectorEntryWithoutStartingNetwork() throws Exception {
        var store=empty(); replace(store,null);
        assertThat(store.status().identityMode()).isEqualTo("AUTO");
        assertThat(store.status().validation()).isEqualTo("LOCAL_VALID");
        store.current().externalCredentials(clock).validateForResolution(clock.instant());
        var handle=new SessionCollectors(store,clock).douyin(ignored -> true);
        assertThat(handle.released().getAsBoolean()).isTrue();
        assertThat(handle.diagnostics().count(cn.bigfish.collector.CollectorDiagnostics.Counter.HANDSHAKE_ATTEMPT)).isZero();
        assertThatThrownBy(() -> replace(store,"invalid-identity")).hasMessage("CREDENTIALS_INVALID");
        replace(store,"9001"); assertThat(store.status().validation()).isEqualTo("LOCAL_VALID");
    }
    @Test void exactMinimumLifetimeUsesOneClockInstantEvenWhenClockAdvancesBetweenReads() {
        Clock moving=new Clock() {
            private Instant at=Instant.parse("2026-11-03T00:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { Instant value=at; at=at.plusMillis(1); return value; }
        };
        var store=new DouyinCredentialSettings(key -> null,moving);
        var value=store.replace(store.status().revision(),true,"fixture","FixtureBrowser/1",null,Duration.ofSeconds(60),null);
        assertThat(Duration.between(value.updatedAt(),value.localExpiresAt())).isEqualTo(Duration.ofSeconds(60));
        assertThat(value.validation()).isEqualTo("LOCAL_VALID");
    }
    @Test void leasesFreezeVersionBlockAllWritesAndOldReleaseCannotUnlockNewOwner() {
        var store=empty(); replace(store,"9001"); var first=store.acquire(Use.INTERACTION);
        assertThat(first.snapshot().version()).isEqualTo(1);
        assertThatThrownBy(() -> store.clear(store.status().revision(),true)).hasMessage("SESSION_ACTIVE");
        assertThatThrownBy(() -> replace(store,"9002")).hasMessage("SESSION_ACTIVE");
        first.stopping(); assertThatThrownBy(() -> store.guest(store.status().revision())).hasMessage("COLLECTOR_STOPPING");
        first.close(); store.guest(store.status().revision()); var second=store.acquire(Use.CHECK);
        first.close(); assertThatThrownBy(() -> store.guest(store.status().revision())).hasMessage("CHECK_ACTIVE");
        second.stopping(); assertThatThrownBy(() -> store.acquire(Use.INTERACTION)).hasMessage("CHECK_STOPPING");
        assertThat(first.snapshot().mode()).isEqualTo(Mode.EXTERNAL); assertThat(second.snapshot().mode()).isEqualTo(Mode.GUEST);
        second.close(); assertThat(store.status().activeUse()).isEqualTo("NONE");
    }
    @Test void concurrentStartAndSaveAreAtomicAndDoNotMixConfigurations() throws Exception {
        var store=empty(); String revision=store.status().revision(); var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=pool.submit(() -> { barrier.await(); return store.acquire(Use.INTERACTION); });
            var update=pool.submit(() -> {
                barrier.await();
                try { store.guest(revision); return "SAVED"; } catch(Failure failure) { return failure.code; }
            });
            try(var lease=start.get(2,TimeUnit.SECONDS)) {
                String result=update.get(2,TimeUnit.SECONDS);
                assertThat(result).isIn("SAVED","SESSION_ACTIVE");
                assertThat(lease.snapshot().version()).isEqualTo(result.equals("SAVED") ? 1 : 0);
                assertThat(store.status().activeVersion()).isEqualTo(lease.snapshot().version());
            }
        }
    }
    @Test void allAccidentalSerializationAndSafeMetadataExcludeSecretsAndManualIdentity() throws Exception {
        var store=empty(); replace(store,"987654321"); var mapper=JsonMapper.builder().findAndAddModules().build();
        try(var lease=store.acquire(Use.INTERACTION)) {
            String serialized=mapper.writeValueAsString(List.of(store,store.current(),lease,store.status()));
            assertThat(serialized).doesNotContain("synthetic-private","987654321").contains("redacted","LOCAL_VALID","RUNTIME");
            assertThat(store.toString()+store.current()+lease+store.status()).doesNotContain("synthetic-private","987654321");
        }
    }
    @Test void eachFactoryProviderKeepsItsCapturedCredentialSourceAcrossLaterSelections() throws Exception {
        var factory=new CollectorCredentialsTest.Factory(CollectorCredentialsTest.external(clock)::get,clock);
        var old=factory.douyin(ignored -> true); var oldSource=factory.source;
        replace(factory.settings(),"9002");
        var next=factory.douyin(ignored -> true); var nextSource=factory.source;
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(oldSource::current).hasMessage("CREDENTIALS_EXPIRED");
        assertThat(nextSource.current()).isNotNull();
        old.port().stop(); next.port().stop();
    }
}
