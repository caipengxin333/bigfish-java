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
    private DeepSeekConnectionProbe probe;
    private volatile ProvidersView.ModelCheck lastCheck;
    public ProviderServices(Environment env, ObjectMapper mapper, LocalTestSpeech testSpeech,
                            @Value("${bigfish.data-dir}") String data) {
        this.env=env; this.mapper=mapper; this.testSpeech=testSpeech; this.audio=Path.of(data,"audio");
    }
    private boolean paid() { return env.getProperty("BIGFISH_ALLOW_PAID",Boolean.class,false); }
    private boolean has(String key) { String value=env.getProperty(key); return value!=null && !value.isBlank(); }
    private boolean nlsConfigured() { return has("BIGFISH_ALIYUN_NLS_APP_KEY") && has("BIGFISH_ALIYUN_NLS_TOKEN"); }
    public synchronized SpeechPort speech(StartSessionRequest.SpeechMode mode, String voice) {
        if (mode==StartSessionRequest.SpeechMode.LOCAL_TEST_TONE) return testSpeech;
        if (!AlibabaNlsSpeech.VOICES.contains(voice)) throw new SessionException("NLS_UNSUPPORTED_VOICE",422);
        if (!paid()) throw new SessionException("PAID_CALLS_DISABLED",503);
        if (!nlsConfigured()) throw new SessionException("NLS_MISSING_CREDENTIALS",503);
        try {
            if (nls==null) nls=AlibabaNlsSpeech.create(true,audio,mapper,env.getProperty("BIGFISH_ALIYUN_NLS_APP_KEY"),
                new StaticNlsTokenProvider(env.getProperty("BIGFISH_ALIYUN_NLS_TOKEN"),null));
            return nls;
        } catch (SpeechFailure failure) { throw new SessionException("NLS_"+failure.code().name(),503); }
    }
    public synchronized ProvidersView status() {
        boolean key=has("BIGFISH_DEEPSEEK_API_KEY"), nlsKey=nlsConfigured();
        String modelState=lastCheck==null ? "UNVERIFIED" : lastCheck.authentication().equals("PASSED") ? "CATALOG_CHECKED" : "CHECK_FAILED";
        String token=nls==null ? "UNVERIFIED" : nls.diagnostics().token().name();
        return new ProvidersView(
            new ProvidersView.Provider(key,key&&paid(),key ? modelState : "MISSING",!key ? "MISSING_CREDENTIALS" : !paid() ? "PAID_CALLS_DISABLED" : lastCheck==null ? "NOT_CHECKED" : lastCheck.code()),
            new ProvidersView.Provider(nlsKey,nlsKey&&paid(),nlsKey ? token : "MISSING",!nlsKey ? "MISSING_CREDENTIALS" : !paid() ? "PAID_CALLS_DISABLED" : nls==null || nls.diagnostics().lastError()==null ? ("ACCEPTED".equals(token) ? "SUCCESS" : "NOT_CHECKED") : "NLS_"+nls.diagnostics().lastError().name()),
            new ProvidersView.Provider(false,false,"UNAVAILABLE","SIGNER_NOT_IMPLEMENTED"),
            AlibabaNlsSpeech.VOICES.stream().sorted().toList(),300,25);
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
