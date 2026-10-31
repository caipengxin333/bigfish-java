package cn.bigfish.core;

import cn.bigfish.configuration.ProviderServices;
import cn.bigfish.contract.StartSessionRequest.SpeechMode;
import cn.bigfish.management.SessionService.SessionException;
import cn.bigfish.speech.LocalTestSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProviderServicesTest {
    @TempDir Path directory;
    @Test void missingCredentialsAndPaidGateDoNotBreakLocalTone() throws Exception {
        var env=new MockEnvironment(); var tone=new LocalTestSpeech(directory.resolve("audio"));
        try(var unused=new Resource(new ProviderServices(env,new ObjectMapper(),tone,directory.toString()))) {
            var services=unused.value;
            assertThat(services.speech(SpeechMode.LOCAL_TEST_TONE,"anything")).isSameAs(tone);
            assertThat(services.status().deepseek().configured()).isFalse();
            assertThatThrownBy(() -> services.speech(SpeechMode.ALIYUN_NLS,"xiaoyun"))
                .isInstanceOf(SessionException.class).hasMessage("PAID_CALLS_DISABLED");
            env.setProperty("BIGFISH_ALLOW_PAID","true");
            assertThatThrownBy(() -> services.speech(SpeechMode.ALIYUN_NLS,"xiaoyun")).hasMessage("NLS_MISSING_CREDENTIALS");
            assertThatThrownBy(() -> services.checkModel("test")).hasMessage("DEEPSEEK_MISSING_CREDENTIALS");
        }
    }
    @Test void presentCredentialsStayUnverifiedAndNeverAppearInStatus() throws Exception {
        var env=new MockEnvironment().withProperty("BIGFISH_ALLOW_PAID","true")
            .withProperty("BIGFISH_DEEPSEEK_API_KEY","fixture-key-only")
            .withProperty("BIGFISH_ALIYUN_NLS_APP_KEY","fixture-app-only")
            .withProperty("BIGFISH_ALIYUN_NLS_TOKEN","fixture-token-only");
        var services=new ProviderServices(env,new ObjectMapper(),new LocalTestSpeech(directory.resolve("audio")),directory.toString());
        try {
            services.speech(SpeechMode.ALIYUN_NLS,"xiaoyun"); // Lazy setup does not make HTTP requests.
            var view=services.status(); assertThat(view.aliyun().state()).isEqualTo("UNVERIFIED");
            assertThat(view.deepseek().state()).isEqualTo("UNVERIFIED"); assertThat(view.aliyun().enabled()).isTrue();
            assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("fixture-");
            assertThatThrownBy(() -> services.speech(SpeechMode.ALIYUN_NLS,"zh-CN-XiaoyiNeural")).hasMessage("NLS_UNSUPPORTED_VOICE");
        } finally { services.close(); }
    }
    private record Resource(ProviderServices value) implements AutoCloseable { public void close() { value.close(); } }
}
