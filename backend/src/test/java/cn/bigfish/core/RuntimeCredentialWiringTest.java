package cn.bigfish.core;

import cn.bigfish.configuration.*;
import cn.bigfish.contract.StartSessionRequest;
import cn.bigfish.management.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"BIGFISH_ALLOW_PAID=false","BIGFISH_DOUYIN_CREDENTIAL_MODE=guest"})
@AutoConfigureMockMvc
@DirtiesContext
class RuntimeCredentialWiringTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bigfish-runtime-credential-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+DATA.resolve("test.db")); r.add("bigfish.data-dir",DATA::toString);
    }
    @Autowired DouyinCredentialSettings settings;
    @Autowired SessionCollectors collectors;
    @Autowired SessionService sessions;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Test void liveBeanUpdatesSurviveRefreshWithoutEnteringOrdinaryApisOrSqliteAndNoEndpointIsPublished() throws Exception {
        assertThat(collectors.settings()).isSameAs(settings);
        settings.replace(settings.status().revision(),true,"private-cookie-fixture","private-agent-fixture","987654321",Duration.ofHours(1),null);
        var selected=settings.status();
        for(String path:new String[]{"config","bootstrap","runtime","providers"}) {
            var response=mvc.perform(get("/api/v1/"+path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("private-cookie","private-agent","987654321","cookieConfigured");
            assertThat(settings.status()).isEqualTo(selected);
        }
        assertThat(jdbc.queryForObject("SELECT content_json FROM app_config WHERE id=1",String.class))
            .doesNotContain("private-cookie","private-agent","987654321","cookieConfigured");
        mvc.perform(get("/api/v1/douyin/credentials")).andExpect(status().isNotFound());
        var running=sessions.start(new StartSessionRequest(StartSessionRequest.Mode.MOCK,StartSessionRequest.ConversationMode.MOCK));
        assertThatThrownBy(() -> settings.clear(selected.revision(),true)).hasMessage("SESSION_ACTIVE");
        sessions.stop(running.sessionId()); settings.clear(selected.revision(),true);
        assertThat(settings.status().source()).isEqualTo(DouyinCredentialSettings.Source.RUNTIME);
        assertThat(collectors.credentialMode()).isEqualTo(SessionCollectors.CredentialMode.GUEST);
        assertThat(mapper.writeValueAsString(settings.status())).doesNotContain("private-","987654321");
    }
}
