package cn.bigfish.core;

import cn.bigfish.management.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="BIGFISH_ALLOW_PAID=false")
@AutoConfigureMockMvc
class SessionApiIntegrationTest {
    static final Path DATA;
    static { try { DATA = Files.createTempDirectory("bigfish-session-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:"+DATA.resolve("test.db"));
        r.add("bigfish.data-dir", DATA::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionService sessions;
    @AfterEach void stop() { sessions.stop(sessions.status().sessionId()); }
    String start() throws Exception {
        var response=mvc.perform(post("/api/v1/sessions").contentType("application/json")
            .content("{\"mode\":\"MOCK\",\"conversationMode\":\"MOCK\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.speechMode").value("LOCAL_TEST_TONE"))
            .andExpect(jsonPath("$.state").value("RUNNING")).andReturn();
        return mapper.readTree(response.getResponse().getContentAsByteArray()).path("sessionId").asText();
    }
    @Test void sessionLifecycleIsIdempotentAndOldStopCannotStopNewSession() throws Exception {
        String first=start();
        mvc.perform(post("/api/v1/sessions").contentType("application/json").content("{\"mode\":\"MOCK\",\"conversationMode\":\"MOCK\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_ACTIVE"));
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/sessions/"+first+"/stop"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED")).andExpect(jsonPath("$.collector.state").value("STOPPED"));
        String second=start(); assertThat(second).isNotEqualTo(first);
        mvc.perform(post("/api/v1/sessions/"+first+"/stop")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.sessionId").value(second)).andExpect(jsonPath("$.state").value("RUNNING"));
    }
    @Test void rejectsUnsupportedRealModeAndPaidCallsWithoutServerOptIn() throws Exception {
        mvc.perform(post("/api/v1/sessions").contentType("application/json").content("{\"mode\":\"DOUYIN\",\"conversationMode\":\"MOCK\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOUYIN_UNAVAILABLE"));
        mvc.perform(post("/api/v1/sessions").contentType("application/json").content("{\"mode\":\"MOCK\",\"conversationMode\":\"DEEPSEEK\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        assertThat(sessions.status().state()).isEqualTo("FAILED");
        start(); // failure is recoverable without restarting Spring
    }
    @Test void inputValidationDedupAndBackpressureUseDocumentedHttpResults() throws Exception {
        String session=start();
        var event=mapper.createObjectNode().put("schemaVersion",1).put("eventId","event-0").put("sessionId",session)
            .put("roomId","mock-room").put("type","COMMENT").put("userId","u").put("username","观众").put("receivedAt",Instant.now().toString());
        event.putObject("payload").put("content","你好");
        for(int i=0;i<9;i++) {
            event.put("eventId","event-"+i);
            mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsString(event)))
                .andExpect(status().isAccepted()); // equal-priority arrival evicts oldest at capacity
        }
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.queues.comments.depth").value(8)).andExpect(jsonPath("$.dropped").value(1));
        mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsString(event)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));
        event.put("sessionId","stale");
        mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsString(event)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.result").value("REJECTED_SESSION"));
        event.put("sessionId",session).put("eventId","invalid"); event.withObject("payload").put("unknown",1);
        mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsString(event)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.result").value("INVALID"));
    }
    @Test void replayRequiresReadyPlayerAndStartRequiresAllPublishedFields() throws Exception {
        for(String body:new String[]{"{}","{\"mode\":\"MOCK\"}","{\"mode\":\"MOCK\",\"conversationMode\":null}","{\"mode\":\"MOCK\",\"conversationMode\":\"MOCK\",\"key\":\"ignored\"}"})
            mvc.perform(post("/api/v1/sessions").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        String session=start();
        mvc.perform(post("/api/v1/sessions/"+session+"/replay")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAYER_NOT_READY"));
    }
    @Test void optionalSpeechModeIsStrictAndProviderChecksNeverEnableCloudCalls() throws Exception {
        for (String voice : new String[]{"null", "\"UNKNOWN\""})
            mvc.perform(post("/api/v1/sessions").contentType("application/json")
                .content("{\"mode\":\"MOCK\",\"conversationMode\":\"MOCK\",\"speechMode\":"+voice+"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/providers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.deepseek.enabled").value(false)).andExpect(jsonPath("$.aliyun.enabled").value(false))
            .andExpect(jsonPath("$.douyin.reason").value("LIVE_RECEIVE_NOT_VERIFIED"))
            .andExpect(jsonPath("$.aliyunToken.attempts").value(0))
            .andExpect(jsonPath("$.aliyunVoices").isArray()).andExpect(jsonPath("$.speechTextLimit").value(300));
        mvc.perform(post("/api/v1/providers/deepseek/check"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PAID_CALLS_DISABLED"));
        String session=start();
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.lastError").value("")).andExpect(jsonPath("$.lastErrorAt").value(""));
    }
    @Test void diagnosticsAreBoundedRedactedAndResetWithTheSession() throws Exception {
        String session=start();
        mvc.perform(get("/api/v1/runtime/diagnostics")).andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(session))
            .andExpect(jsonPath("$.waiting").value(org.hamcrest.Matchers.hasItem("NO_MESSAGES")))
            .andExpect(jsonPath("$.waiting").value(org.hamcrest.Matchers.hasItem("PLAYER_NOT_READY")));
        var event=mapper.createObjectNode().put("schemaVersion",1).put("sessionId",session).put("roomId","mock-room")
            .put("type","COMMENT").put("userId","sensitive-user-fixture").put("username","sensitive-name-fixture").put("receivedAt",Instant.now().toString());
        event.putObject("payload").put("content","sensitive-comment-fixture");
        for(int i=0;i<15;i++) {
            event.put("eventId","external-event-fixture-"+i);
            mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsString(event)))
                .andExpect(status().isAccepted());
        }
        var response=mvc.perform(get("/api/v1/runtime/diagnostics")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("sensitive-","external-event-fixture-");
        var view=mapper.readTree(response);
        assertThat(view.path("recent").size()).isEqualTo(20);
        assertThat(view.path("waiting").toString()).contains("MODEL_WAIT");
        assertThat(view.path("recent").toString()).contains("QUEUE_FULL");
        sessions.stop(session); String next=start();
        mvc.perform(get("/api/v1/runtime/diagnostics")).andExpect(jsonPath("$.sessionId").value(next))
            .andExpect(jsonPath("$.recent").isEmpty()).andExpect(jsonPath("$.lastFailure").isEmpty());
    }
}
