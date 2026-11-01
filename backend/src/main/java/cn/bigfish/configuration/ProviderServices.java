package cn.bigfish.configuration;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.*;
import cn.bigfish.speech.*;
import cn.bigfish.management.SessionService.SessionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ProviderServices {
    private final Environment env;
    private final ObjectMapper mapper;
    private final Path audio;
    private final LocalTestSpeech testSpeech;
    private AlibabaNlsSpeech nls;
    private NlsTokenProviders.Handle tokens;
    private DeepSeekConnectionProbe probe;
    private volatile ProvidersView.ModelCheck lastCheck;
    public ProviderServices(Environment env, ObjectMapper mapper, LocalTestSpeech testSpeech,
                            @Value("${bigfish.data-dir}") String data) {
        this.env=env; this.mapper=mapper; this.testSpeech=testSpeech; this.audio=Path.of(data,"audio");
    }
    private boolean paid() { return env.getProperty("BIGFISH_ALLOW_PAID",Boolean.class,false); }
    private boolean has(String key) { String value=env.getProperty(key); return value!=null && !value.isBlank(); }
    private record NlsConfiguration(String mode, String error) {
        boolean configured() { return error.isEmpty(); }
    }
    private NlsConfiguration nlsConfiguration() {
        NlsTokenProviders.Mode mode;
        try { mode=NlsTokenProviders.mode(env.getProperty("BIGFISH_ALIYUN_NLS_TOKEN_MODE")); }
        catch (SpeechFailure failure) { return new NlsConfiguration("INVALID","TOKEN_MODE_INVALID"); }
        boolean credentials=has("BIGFISH_ALIYUN_NLS_APP_KEY") && (mode==NlsTokenProviders.Mode.STATIC
            ? has("BIGFISH_ALIYUN_NLS_TOKEN")
            : has("BIGFISH_ALIYUN_ACCESS_KEY_ID") && has("BIGFISH_ALIYUN_ACCESS_KEY_SECRET"));
        return new NlsConfiguration(mode.name(),credentials ? "" : "MISSING_CREDENTIALS");
    }
    public synchronized SpeechPort speech(StartSessionRequest.SpeechMode mode, String voice) {
        if (mode==StartSessionRequest.SpeechMode.LOCAL_TEST_TONE) return testSpeech;
        if (!AlibabaNlsSpeech.VOICES.contains(voice)) throw new SessionException("NLS_UNSUPPORTED_VOICE",422);
        if (!paid()) throw new SessionException("PAID_CALLS_DISABLED",503);
        var configuration=nlsConfiguration();
        if (!configuration.configured()) throw new SessionException("NLS_"+configuration.error(),503);
        try {
            if (nls==null) {
                var created=NlsTokenProviders.create(true,env::getProperty,mapper);
                try {
                    nls=AlibabaNlsSpeech.create(true,audio,mapper,env.getProperty("BIGFISH_ALIYUN_NLS_APP_KEY"),created);
                    tokens=created;
                } catch (RuntimeException failure) { created.close(); throw failure; }
            }
            return nls;
        } catch (SpeechFailure failure) { throw new SessionException("NLS_"+failure.code().name(),503); }
    }
    public synchronized ProvidersView status() {
        return status(new ProvidersView.Provider(true,true,"UNVERIFIED","LIVE_RECEIVE_NOT_VERIFIED"));
    }
    public synchronized ProvidersView status(ProvidersView.Provider douyin) {
        var configuration=nlsConfiguration();
        boolean key=has("BIGFISH_DEEPSEEK_API_KEY"), nlsKey=configuration.configured();
        String modelState=lastCheck==null ? "UNVERIFIED" : lastCheck.authentication().equals("PASSED") ? "CATALOG_CHECKED" : "CHECK_FAILED";
        var token=tokenStatus(configuration);
        String nlsState=!nlsKey ? "MISSING" : tokens==null ? "UNVERIFIED"
            : configuration.mode().equals("RAM") && token.status().equals("MISSING") ? "AWAITING_TOKEN" : token.status();
        String nlsReason=!nlsKey ? configuration.error() : !paid() ? "PAID_CALLS_DISABLED"
            : !token.lastError().isEmpty() ? token.lastError()
            : nls!=null && nls.diagnostics().lastError()!=null ? "NLS_"+nls.diagnostics().lastError().name()
            : token.status().equals("ACCEPTED") ? "SUCCESS" : "NOT_CHECKED";
        return new ProvidersView(
            new ProvidersView.Provider(key,key&&paid(),key ? modelState : "MISSING",!key ? "MISSING_CREDENTIALS" : !paid() ? "PAID_CALLS_DISABLED" : lastCheck==null ? "NOT_CHECKED" : lastCheck.code()),
            new ProvidersView.Provider(nlsKey,nlsKey&&paid(),nlsState,nlsReason),
            douyin,
            AlibabaNlsSpeech.VOICES.stream().sorted().toList(),300,25,token);
    }
    private ProvidersView.TokenState tokenStatus(NlsConfiguration configuration) {
        if (tokens==null) return new ProvidersView.TokenState(configuration.mode(),configuration.configured()
            ? "NOT_REQUESTED" : "MISSING_CONFIGURATION",null,null,"NOT_REQUESTED",
            configuration.error().isEmpty() ? "" : "NLS_"+configuration.error(),null,null,0,0);
        var snapshot=tokens.snapshot();
        return new ProvidersView.TokenState(snapshot.mode().name(),snapshot.status().name(),snapshot.expiresAt(),
            snapshot.remainingSeconds(),snapshot.refreshResult().name(),snapshot.lastError()==null ? "" : "NLS_"+snapshot.lastError().name(),
            snapshot.lastAttemptAt(),snapshot.lastSuccessAt(),snapshot.retryAfterMillis(),snapshot.attempts());
    }
    /** Explicit catalog check only; polling status never calls the provider. */
    public ProvidersView.ModelCheck checkModel(String model) {
        DeepSeekConnectionProbe client;
        synchronized (this) {
            if (!paid()) throw new SessionException("PAID_CALLS_DISABLED",503);
            if (!has("BIGFISH_DEEPSEEK_API_KEY")) throw new SessionException("DEEPSEEK_MISSING_CREDENTIALS",503);
            if (probe==null) probe=DeepSeekConnectionProbe.create(true,env.getProperty("BIGFISH_DEEPSEEK_API_KEY"));
            client=probe;
        }
        String id=UUID.randomUUID().toString();
        try {
            var result=client.check(id,model,Duration.ofSeconds(10)).toCompletableFuture().get(11,TimeUnit.SECONDS);
            var mapped=new ProvidersView.ModelCheck(result.authentication().name(),result.selectedModel().name(),result.generation().name(),
                result.availableModels(),result.diagnostic().code().name(),result.diagnostic().elapsedMs());
            lastCheck=mapped; return mapped;
        } catch (Exception error) {
            client.cancel(id);
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            String code="MODEL_CHECK_FAILED";
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) if(cause instanceof DeepSeekFailure known) { code=known.diagnostic().code().name(); break; }
            throw new SessionException(code,503);
        }
    }
    @PreDestroy public synchronized void close() { if(probe!=null) probe.close(); if(nls!=null) nls.close(); }
}
