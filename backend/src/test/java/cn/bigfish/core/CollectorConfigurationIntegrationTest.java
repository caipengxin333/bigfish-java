package cn.bigfish.core;

import cn.bigfish.configuration.SessionCollectors;
import cn.bigfish.management.SessionService;
import cn.bigfish.management.diagnostics.GiftTraceLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"BIGFISH_ALLOW_PAID=false","BIGFISH_DOUYIN_CREDENTIAL_MODE=external",
    "BIGFISH_DOUYIN_COOKIE=","BIGFISH_DOUYIN_USER_AGENT=","BIGFISH_DOUYIN_USER_UNIQUE_ID=",
    "BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT=2030-01-01T00:00:00Z","BIGFISH_GIFT_TRACE_PERSIST=true"})
@AutoConfigureMockMvc
@DirtiesContext
class CollectorConfigurationIntegrationTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bigfish-credential-wiring-"); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+DATA.resolve("test.db"));
        r.add("bigfish.data-dir",DATA::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionCollectors collectors;
    @Autowired SessionService sessions;
    @Autowired GiftTraceLog traceLog;

    @Test void environmentWiringIsLazyAndMissingExternalStillAllowsMockWithLocalGiftFiles() throws Exception {
        assertThat(collectors.credentialMode()).isEqualTo(SessionCollectors.CredentialMode.EXTERNAL);
        assertThat(collectors.configurationStatus()).isEqualTo("CREDENTIALS_MISSING");
        for(String path:new String[]{"bootstrap","providers","config","runtime"})
            mvc.perform(get("/api/v1/"+path)).andExpect(status().isOk());
        assertThat(sessions.status().state()).isEqualTo("STOPPED");
        assertThat(traceLog.status().files()).isEqualTo(GiftTraceLog.FileState.ACTIVE);

        var config=mapper.readTree(mvc.perform(get("/api/v1/config")).andReturn().getResponse().getContentAsByteArray());
        config.withObject("settings").put("roomId","123456");
        var update=mapper.createObjectNode().put("expectedVersion",config.path("version").asLong()); update.set("settings",config.get("settings"));
        mvc.perform(put("/api/v1/config").contentType("application/json").content(mapper.writeValueAsBytes(update))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sessions").contentType("application/json")
            .content("{\"mode\":\"DOUYIN\",\"conversationMode\":\"MOCK\",\"speechMode\":\"LOCAL_TEST_TONE\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DOUYIN_CREDENTIALS_MISSING"));
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.lastError").value("COLLECTOR_FAILED"))
            .andExpect(jsonPath("$.collector.lastError").value("CREDENTIALS_MISSING"))
            .andExpect(jsonPath("$.collector.received").value(0));
        mvc.perform(post("/api/v1/sessions").contentType("application/json")
            .content("{\"mode\":\"MOCK\",\"conversationMode\":\"MOCK\",\"speechMode\":\"LOCAL_TEST_TONE\"}"))
            .andExpect(status().isCreated());
        String session=sessions.status().sessionId();
        try {
            var event=mapper.createObjectNode().put("schemaVersion",1).put("sessionId",session).put("roomId","mock-room")
                .put("type","GIFT").put("userId","synthetic-private-user").put("username","synthetic-private-name").put("receivedAt",Instant.now().toString());
            event.putObject("payload").put("giftId","fixture").put("giftName","synthetic-private-gift").put("quantity",1)
                .put("comboId","").put("unitValue",1).put("valueUnit","DOUYIN_DIAMOND");
            for(int i=0;i<5;i++) {
                event.put("eventId","synthetic-private-event-"+i);
                mvc.perform(post("/api/v1/events/simulate").contentType("application/json").content(mapper.writeValueAsBytes(event)))
                    .andExpect(status().isAccepted());
            }
        } finally { sessions.stop(session); }
        traceLog.close();
        assertThat(traceLog.status().workerAlive()).isFalse(); assertThat(traceLog.status().dropped()).isZero();
        var lines=Files.readAllLines(DATA.resolve("diagnostics/gifts/gift-trace-0.jsonl"));
        assertThat(lines.size()).isGreaterThan(20);
        String text=String.join("\n",lines);
        assertThat(text).contains(session,"SESSION_STOPPED","RECEIVED").doesNotContain("synthetic-private","Cookie");
        assertThat(sessions.status().player().connected()).isFalse();
    }
}
