package cn.bigfish.core;

import cn.bigfish.configuration.SessionCollectors;
import cn.bigfish.contract.CollectorPort;
import cn.bigfish.management.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="BIGFISH_ALLOW_PAID=false")
@AutoConfigureMockMvc
class DouyinSessionApiTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bigfish-douyin-session-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+DATA.resolve("test.db"));
        r.add("bigfish.data-dir",DATA::toString);
    }
    @TestConfiguration static class LocalOnly {
        @Bean @Primary SessionCollectors fixtureCollectors() { return new DouyinSessionTest.Factory(); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionService sessions;
    @Autowired SessionCollectors collectors;
    @AfterEach void stop() { sessions.stop(sessions.status().sessionId()); }

    @Test void explicitDouyinUsesSavedRoomAndIndependentLocalModesWhilePollingStaysOffline() throws Exception {
        var factory=(DouyinSessionTest.Factory)collectors;
        var config=mapper.readTree(mvc.perform(get("/api/v1/config")).andReturn().getResponse().getContentAsByteArray());
        var settings=config.withObject("settings"); settings.put("roomId","549511642024");
        var update=mapper.createObjectNode().put("expectedVersion",config.path("version").asLong()); update.set("settings",settings);
        mvc.perform(put("/api/v1/config").contentType("application/json").content(mapper.writeValueAsBytes(update))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bootstrap")).andExpect(jsonPath("$.capabilities.douyinCollector").value(true));
        mvc.perform(get("/api/v1/providers")).andExpect(jsonPath("$.douyin.state").value("UNVERIFIED"))
            .andExpect(jsonPath("$.douyin.enabled").value(true)).andExpect(jsonPath("$.aliyunToken.attempts").value(0));
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.state").value("STOPPED"));
        assertThat(factory.douyinCalls).isZero(); assertThat(factory.mockCalls).isZero();
        mvc.perform(post("/api/v1/sessions").contentType("application/json")
            .content("{\"mode\":\"DOUYIN\",\"conversationMode\":\"MOCK\",\"speechMode\":\"LOCAL_TEST_TONE\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mode").value("DOUYIN"))
            .andExpect(jsonPath("$.conversationMode").value("MOCK")).andExpect(jsonPath("$.speechMode").value("LOCAL_TEST_TONE"))
            .andExpect(jsonPath("$.collector.state").value("CONNECTING")).andExpect(jsonPath("$.roomId").value(""));
        assertThat(factory.douyinCalls).isEqualTo(1); assertThat(factory.mockCalls).isZero();
        assertThat(factory.current.requested).isEqualTo("549511642024"); assertThat(factory.binding.test("901")).isTrue();
        factory.current.state=CollectorPort.State.CONNECTED;
        mvc.perform(get("/api/v1/runtime")).andExpect(jsonPath("$.roomId").value("901"))
            .andExpect(jsonPath("$.collector.mode").value("DOUYIN")).andExpect(jsonPath("$.collector.state").value("CONNECTED"))
            .andExpect(jsonPath("$.collector.received").value(0));
        mvc.perform(get("/api/v1/providers")).andExpect(jsonPath("$.douyin.state").value("UNVERIFIED"))
            .andExpect(jsonPath("$.douyin.reason").value("LIVE_RECEIVE_NOT_VERIFIED"));
        mvc.perform(post("/api/v1/sessions/"+sessions.status().sessionId()+"/stop"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));
        assertThat(factory.current.stopped).isTrue();
    }
}
