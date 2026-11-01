package cn.bigfish;

import cn.bigfish.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ManagementIntegrationTest {
    private static final Path DB;
    static { try { DB = Files.createTempFile("bigfish-config-test-", ".db"); }
             catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("bigfish.data-dir", () -> DB.toString() + "-data");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Test void configPersistsAndRejectsStaleVersion() throws Exception {
        var response = mvc.perform(get("/api/v1/config")).andExpect(status().isOk()).andReturn();
        var before = mapper.readValue(response.getResponse().getContentAsByteArray(), ConfigSnapshot.class);
        var payload = mapper.writeValueAsString(new UpdateConfigRequest(before.version(), before.settings()));
        mvc.perform(put("/api/v1/config").contentType("application/json").content(payload))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(before.version()+1));
        mvc.perform(get("/api/v1/config")).andExpect(jsonPath("$.version").value(before.version()+1));
        mvc.perform(put("/api/v1/config").contentType("application/json").content(payload))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_CONFLICT"));
    }
    @Test void rejectsInvalidSettings() throws Exception {
        var settings = mapper.valueToTree(RuntimeSettings.defaults());
        ((com.fasterxml.jackson.databind.node.ObjectNode)settings).put("synthesisQueueCapacity", 0);
        var body = mapper.createObjectNode().put("expectedVersion",1).set("settings",settings);
        mvc.perform(put("/api/v1/config").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }
    @Test void bootstrapDoesNotAdvertiseUnimplementedFeatures() throws Exception {
        mvc.perform(get("/api/v1/bootstrap")).andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("READY"))
            .andExpect(jsonPath("$.capabilities.douyinCollector").value(true));
    }
    @Test void ignoresNoSecretFields() throws Exception {
        mvc.perform(put("/api/v1/config").contentType("application/json")
            .content("{\"expectedVersion\":1,\"apiKey\":\"not-a-real-key\",\"settings\":null}"))
            .andExpect(status().isBadRequest());
    }
    @Test void rejectsMissingBooleanAndNullNumericFields() throws Exception {
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(RuntimeSettings.defaults());
        settings.remove("readComment");
        var body = mapper.createObjectNode().put("expectedVersion",1).set("settings",settings);
        mvc.perform(put("/api/v1/config").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        settings.put("readComment", false);
        ((com.fasterxml.jackson.databind.node.ObjectNode)settings.get("priorities")).putNull("gift");
        mvc.perform(put("/api/v1/config").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

}
