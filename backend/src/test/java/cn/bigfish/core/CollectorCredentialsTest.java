package cn.bigfish.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.bigfish.collector.*;
import cn.bigfish.configuration.*;
import cn.bigfish.contract.*;
import cn.bigfish.management.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorCredentialsTest {
    @TempDir Path directory;
    static Map<String,String> external(CoreRig.MutableClock clock) {
        return new HashMap<>(Map.of("BIGFISH_DOUYIN_CREDENTIAL_MODE","external",
            "BIGFISH_DOUYIN_COOKIE","synthetic-private-cookie=fixture-only",
            "BIGFISH_DOUYIN_USER_AGENT","synthetic-private-agent",
            "BIGFISH_DOUYIN_USER_UNIQUE_ID","90000001",
            "BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT",clock.instant().plusSeconds(60).toString()));
    }
    static class Factory extends SessionCollectors {
        int guestCalls, externalCalls;
        WebDouyinConnectionProvider.CredentialSource source;
        DouyinSigner signer;
        final WebDouyinConnectionProvider provider=org.mockito.Mockito.mock(WebDouyinConnectionProvider.class);
        Factory(Function<String,String> env, CoreRig.MutableClock clock) { super(env,clock); }
        @Override protected WebDouyinConnectionProvider guestProvider(DouyinSigner signer) {
            guestCalls++; this.signer=signer; return provider;
        }
        @Override protected WebDouyinConnectionProvider externalProvider(WebDouyinConnectionProvider.CredentialSource source,DouyinSigner signer) {
            externalCalls++; this.source=source; this.signer=signer; return provider;
        }
    }

    @Test void guestNeverReadsExternalSecretsAndFactoriesRemainLazy() throws Exception {
        var factory=new Factory(key -> {
            assertThat(key).isEqualTo("BIGFISH_DOUYIN_CREDENTIAL_MODE"); return null;
        },new CoreRig.MutableClock());
        assertThat(factory.configurationStatus()).isEqualTo("GUEST_AUTO");
        var handle=factory.douyin(ignored -> true);
        assertThat(factory.guestCalls).isEqualTo(1); assertThat(factory.externalCalls).isZero();
        assertThat(handle.port().state()).isEqualTo(CollectorPort.State.STOPPED);
        verifyNoInteractions(factory.provider);
        handle.port().stop();
    }

    @Test void externalUsesExistingProviderSourceAndRevalidatesExpiryWithoutFallback() throws Exception {
        var clock=new CoreRig.MutableClock(); var env=external(clock); var factory=new Factory(env::get,clock);
        assertThat(factory.configurationStatus()).isEqualTo("LOCAL_VALID");
        var handle=factory.douyin(ignored -> true);
        assertThat(factory.externalCalls).isEqualTo(1); assertThat(factory.guestCalls).isZero();
        assertThat(factory.source.current().toString()).isEqualTo("DouyinCredentials[redacted]");
        when(factory.provider.resolve(anyString(),nullable(String.class))).thenAnswer(call -> {
            factory.source.current(); return DouyinConnection.ended("123");
        });
        clock.advance(Duration.ofSeconds(61));
        handle.port().start("123456","synthetic-session",event -> { throw new AssertionError(); });
        long until=System.nanoTime()+2_000_000_000L;
        while (!handle.released().getAsBoolean() && System.nanoTime()<until) Thread.sleep(5);
        assertThat(handle.port().state()).isEqualTo(CollectorPort.State.FAILED);
        assertThat(handle.diagnostics().lastError()).isEqualTo("CREDENTIALS_EXPIRED");
        assertThat(handle.released().getAsBoolean()).isTrue(); assertThat(factory.guestCalls).isZero();
        handle.port().stop();
    }

    @Test void missingInvalidExpiredAndUnknownModesFailBeforeProviderConstruction() {
        var clock=new CoreRig.MutableClock();
        var cases=new LinkedHashMap<String,Map<String,String>>();
        cases.put("CREDENTIALS_MISSING",Map.of("BIGFISH_DOUYIN_CREDENTIAL_MODE","external"));
        var invalid=external(clock); invalid.put("BIGFISH_DOUYIN_COOKIE","synthetic\r\nheader"); cases.put("CREDENTIALS_INVALID",invalid);
        var expired=external(clock); expired.put("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT",clock.instant().toString()); cases.put("CREDENTIALS_EXPIRED",expired);
        cases.put("CREDENTIAL_MODE_INVALID",Map.of("BIGFISH_DOUYIN_CREDENTIAL_MODE","unexpected-sensitive-input"));
        cases.forEach((code,env) -> {
            var factory=new Factory(env::get,clock);
            assertThat(factory.configurationStatus()).isEqualTo(code);
            assertThatThrownBy(() -> factory.douyin(ignored -> true)).isInstanceOf(SessionCollectors.ConfigurationFailure.class)
                .hasMessage(code).hasNoCause();
            assertThat(factory.guestCalls+factory.externalCalls).isZero(); verifyNoInteractions(factory.provider);
        });
        for(String expiry:List.of("not-a-date","")) {
            var env=external(clock); env.put("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT",expiry);
            assertThat(new Factory(env::get,clock).configurationStatus()).isEqualTo("CREDENTIALS_INVALID");
        }
    }

    @Test void startupLogContainsOnlyLocalModeAndValidationEvidence() {
        Logger logger=(Logger)LoggerFactory.getLogger(SessionCollectors.class);
        var output=new ListAppender<ILoggingEvent>(); output.start(); logger.addAppender(output);
        try {
            var clock=new CoreRig.MutableClock(); var env=external(clock);
            new Factory(env::get,clock);
            String text=output.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+b);
            assertThat(text).contains("mode=EXTERNAL","validation=LOCAL_VALID","platform=UNVERIFIED")
                .doesNotContain("synthetic-private", "90000001",env.get("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT"));
        } finally { logger.detachAppender(output); output.stop(); }
    }

    @Test void brokenEnvironmentCannotLeakPropertyValuesOrBreakMockStartup() {
        var clock=new CoreRig.MutableClock();
        var mode=new Factory(key -> { throw new IllegalArgumentException("private-property-value"); },clock);
        assertThat(mode.configurationStatus()).isEqualTo("CREDENTIAL_MODE_INVALID");
        var credentials=new Factory(key -> {
            if(key.equals("BIGFISH_DOUYIN_CREDENTIAL_MODE")) return "external";
            throw new IllegalArgumentException("private-cookie-interpolation");
        },clock);
        assertThat(credentials.configurationStatus()).isEqualTo("CREDENTIALS_INVALID");
        assertThatThrownBy(() -> credentials.douyin(ignored -> true)).hasMessage("CREDENTIALS_INVALID").hasNoCause();
    }

    @Test void sessionReportsCredentialRootCauseAndMockRemainsUsable() throws Exception {
        var cases=List.of(Map.of("BIGFISH_DOUYIN_CREDENTIAL_MODE","external"),
            Map.of("BIGFISH_DOUYIN_CREDENTIAL_MODE","bad-mode"));
        int index=0;
        for(var env:cases) {
            Path data=java.nio.file.Files.createDirectory(directory.resolve("case"+index++));
            try(var rig=new CoreRig(data,new cn.bigfish.conversation.MockConversation())) {
                rig.engine.stop(rig.session);
                var factory=new Factory(env::get,rig.clock);
                var providers=new ProviderServices(new MockEnvironment(),new ObjectMapper(),rig.speech,data.toString());
                try {
                    var sessions=new SessionService(rig.engine,rig.configs,providers,factory);
                    var c=rig.configs.current(); var s=c.settings();
                    rig.configs.update(new UpdateConfigRequest(c.version(),new RuntimeSettings(s.profileName(),s.persona(),"123456",
                        s.model(),s.maxOutputTokens(),s.voice(),s.readComment(),s.welcomeIntervalSeconds(),s.synthesisQueueCapacity(),s.playbackQueueCapacity(),s.priorities())));
                    String code=factory.configurationStatus();
                    assertThatThrownBy(() -> sessions.start(DouyinSessionTest.DOUYIN)).hasMessage("DOUYIN_"+code);
                    assertThat(sessions.status().state()).isEqualTo("FAILED");
                    assertThat(sessions.status().collector().lastError()).isEqualTo(code);
                    assertThat(sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
                    assertThat(sessions.status().player().ready()).isFalse();
                    assertThat(factory.externalCalls+factory.guestCalls).isZero();
                    assertThat(sessions.start(DouyinSessionTest.MOCK).state()).isEqualTo("RUNNING");
                    sessions.close();
                } finally { providers.close(); }
            }
        }
    }
}
