package cn.bigfish.core;

import cn.bigfish.collector.CollectorDiagnostics;
import cn.bigfish.configuration.*;
import cn.bigfish.contract.*;
import cn.bigfish.management.*;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real controller, service and collector lease together, without cloud requests. */
class DouyinAccessControllerTest {
    static final String ROOT="/api/v1/douyin/credentials";
    final Clock clock=Clock.systemUTC();
    final DouyinCredentialSettings settings=new DouyinCredentialSettings(key -> null,clock);
    final SessionService sessions=mock(SessionService.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    final jakarta.validation.ValidatorFactory validators=Validation.buildDefaultValidatorFactory();
    final Source source=new Source();
    final DouyinConnectionChecks checks=new DouyinConnectionChecks(settings,s -> source.handle(),clock);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new DouyinAccessController(settings,checks,sessions,mapper,validators.getValidator()))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    static class Source implements CollectorPort {
        volatile State state=State.STOPPED;
        volatile boolean released=true;
        volatile String session;
        volatile Consumer<LiveEvent> sink;
        public void start(String room,String session,Consumer<LiveEvent> sink) { released=false; this.session=session; this.sink=sink; state=State.CONNECTED; }
        public void stop() { state=State.STOPPED; released=true; }
        public State state() { return state; }
        SessionCollectors.Handle handle() { return new SessionCollectors.Handle(this,new CollectorDiagnostics(),() -> released); }
        void emit(LiveEvent.Type type) { sink.accept(new LiveEvent(1,"fixture-event",session,"123",type,"fixture-user","fixture-name",Instant.now(),Map.of())); }
    }
    @AfterEach void close() { checks.close(); validators.close(); }
    MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.header("Host","localhost:15173").header("Origin","http://localhost:15173")
            .header("X-BigFish-Local-Management","1").contentType("application/json");
    }
    String replacement(String revision) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedRevision",revision,"replace",true,"cookie","fixture_cookie=not-real",
            "userAgent","FixtureBrowser/1.0","localLifetimeSeconds",3600));
    }
    @Test void atomicReplacementClearAndCasNeverEchoSensitiveFields() throws Exception {
        String before=settings.status().revision();
        String body=mvc.perform(local(put(ROOT+"/cookie")).content(replacement(before))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.mode").value("EXTERNAL")).andExpect(jsonPath("$.identityMode").value("AUTO"))
            .andExpect(jsonPath("$.validation").value("LOCAL_VALID")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("fixture_cookie","FixtureBrowser","userAgent","userUniqueIdOverride");
        String revision=settings.status().revision();
        mvc.perform(local(put(ROOT+"/cookie")).content(replacement(before))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CREDENTIAL_CONFIG_CONFLICT"));
        mvc.perform(local(post(ROOT+"/clear")).content(mapper.writeValueAsString(Map.of("expectedRevision",revision,"confirm",true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("GUEST"))
            .andExpect(jsonPath("$.source").value("RUNTIME")).andExpect(jsonPath("$.cookieConfigured").value(false));
        assertThat(settings.status().revision()).isNotEqualTo(revision);
    }
    @Test void guardRunsBeforeJsonParsingAndErrorsAreFixedAndNoncacheable() throws Exception {
        mvc.perform(put(ROOT+"/cookie").content("fixture-sensitive-broken-json"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(sessions);
        mvc.perform(local(put(ROOT+"/cookie")).with(r -> { r.setRemoteAddr("192.0.2.10"); return r; }).content("{"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ACCESS_REQUIRED"));
        mvc.perform(local(put(ROOT+"/cookie")).header("X-Forwarded-For","127.0.0.1").content("{"))
            .andExpect(status().isForbidden());
        for(String body:new String[]{"fixture-sensitive-broken-json","null","{} {}","{\"cookie\":\"fixture\",\"cookie\":\"again\"}",
            replacement(settings.status().revision()).replace("\"replace\":true","\"unknownSecret\":\"fixture\",\"replace\":true")}) {
            mvc.perform(local(put(ROOT+"/cookie")).content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}"));
        }
        assertThat(settings.status().mode()).isEqualTo(DouyinCredentialSettings.Mode.GUEST);
        mvc.perform(local(put(ROOT+"/cookie")).content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
    }
    @Test void activeInteractionAndCheckRejectMutationWhileCheckReceivesOnlySafeEvidence() throws Exception {
        try(var lease=settings.acquire(DouyinCredentialSettings.Use.INTERACTION)) {
            mvc.perform(local(put(ROOT+"/cookie")).content(replacement(settings.status().revision())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_ACTIVE"));
        }
        String result=mvc.perform(local(post(ROOT+"/checks")).content(mapper.writeValueAsString(Map.of(
            "expectedRevision",settings.status().revision(),"roomId","123","durationSeconds",20))))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(result).path("checkId").asText();
        long deadline=System.nanoTime()+2_000_000_000L;
        while(source.state!=CollectorPort.State.CONNECTED && System.nanoTime()<deadline) Thread.sleep(2);
        assertThat(source.state).isEqualTo(CollectorPort.State.CONNECTED);
        source.emit(LiveEvent.Type.COMMENT); source.emit(LiveEvent.Type.GIFT);
        mvc.perform(local(get(ROOT+"/checks/"+id))).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.comments").value(1)).andExpect(jsonPath("$.gifts").value(1));
        mvc.perform(local(put(ROOT+"/cookie")).content(replacement(settings.status().revision())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECK_ACTIVE"));
        mvc.perform(local(post(ROOT+"/checks/"+id+"/cancel")).content("{}")).andExpect(status().isOk());
        deadline=System.nanoTime()+2_000_000_000L;
        while(!checks.status(id).resourcesReleased() && System.nanoTime()<deadline) Thread.sleep(2);
        mvc.perform(local(get(ROOT+"/checks/"+id))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
            .andExpect(jsonPath("$.resourcesReleased").value(true));
        assertThat(settings.status().activeUse()).isEqualTo("NONE");
        verify(sessions,atLeastOnce()).reconcile(); verifyNoMoreInteractions(sessions);
    }
    @Test void writeOnlyRecordCannotSerializeCredentialValues() throws Exception {
        var record=mapper.readerFor(DouyinAccessContract.Replace.class).without(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .<DouyinAccessContract.Replace>readValue(replacement(settings.status().revision()));
        assertThat(mapper.writeValueAsString(record)).doesNotContain("fixture_cookie","FixtureBrowser","userAgent","userUniqueIdOverride");
        assertThat(record.toString()).isEqualTo("DouyinCredentialReplace[redacted]");
    }
}
