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
    @Test void ramModeIsLazyAndDoesNotRequireOrFallBackToStaticToken() throws Exception {
        var env=new MockEnvironment().withProperty("BIGFISH_ALLOW_PAID","true")
            .withProperty("BIGFISH_ALIYUN_NLS_TOKEN_MODE","RAM")
            .withProperty("BIGFISH_ALIYUN_NLS_APP_KEY","fixture-app")
            .withProperty("BIGFISH_ALIYUN_ACCESS_KEY_ID","fixture-id")
            .withProperty("BIGFISH_ALIYUN_ACCESS_KEY_SECRET","fixture-secret");
        var services=new ProviderServices(env,new ObjectMapper(),new LocalTestSpeech(directory.resolve("audio")),directory.toString());
        try {
            assertThat(services.status().aliyun().enabled()).isTrue();
            assertThat(services.status().aliyunToken().status()).isEqualTo("NOT_REQUESTED");
            var port=services.speech(SpeechMode.ALIYUN_NLS,"zhigui");
            assertThat(services.speech(SpeechMode.ALIYUN_NLS,"xiaoyun")).isSameAs(port);
            for(int i=0;i<3;i++) {
                var view=services.status();
                assertThat(view.aliyun().state()).isEqualTo("AWAITING_TOKEN");
                assertThat(view.aliyunToken().mode()).isEqualTo("RAM");
                assertThat(view.aliyunToken().refreshResult()).isEqualTo("NOT_REQUESTED");
                assertThat(view.aliyunToken().attempts()).isZero();
                assertThat(view.aliyunToken().expiresAt()).isNull();
                assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("fixture-");
            }
        } finally { services.close(); }
        var missing=new MockEnvironment().withProperty("BIGFISH_ALLOW_PAID","true")
            .withProperty("BIGFISH_ALIYUN_NLS_TOKEN_MODE","RAM")
            .withProperty("BIGFISH_ALIYUN_NLS_APP_KEY","fixture-app")
            .withProperty("BIGFISH_ALIYUN_NLS_TOKEN","fixture-static-must-not-be-used");
        var unavailable=new ProviderServices(missing,new ObjectMapper(),new LocalTestSpeech(directory.resolve("other")),directory.toString());
        try {
            assertThat(unavailable.status().aliyun().enabled()).isFalse();
            assertThatThrownBy(() -> unavailable.speech(SpeechMode.ALIYUN_NLS,"zhigui")).hasMessage("NLS_MISSING_CREDENTIALS");
        } finally { unavailable.close(); }
    }
    @Test void invalidTokenModeAndDisabledRamAreSafeToPoll() throws Exception {
        var env=new MockEnvironment().withProperty("BIGFISH_ALLOW_PAID","true")
            .withProperty("BIGFISH_ALIYUN_NLS_TOKEN_MODE","untrusted-value");
        var services=new ProviderServices(env,new ObjectMapper(),new LocalTestSpeech(directory.resolve("audio")),directory.toString());
        try {
            assertThat(services.status().aliyunToken().mode()).isEqualTo("INVALID");
            assertThat(services.status().aliyun().enabled()).isFalse();
            assertThat(new ObjectMapper().writeValueAsString(services.status())).doesNotContain("untrusted-value");
            assertThatThrownBy(() -> services.speech(SpeechMode.ALIYUN_NLS,"zhigui")).hasMessage("NLS_TOKEN_MODE_INVALID");
            env.setProperty("BIGFISH_ALIYUN_NLS_TOKEN_MODE","RAM");
            env.setProperty("BIGFISH_ALLOW_PAID","false");
            assertThatThrownBy(() -> services.speech(SpeechMode.ALIYUN_NLS,"zhigui")).hasMessage("PAID_CALLS_DISABLED");
            assertThat(services.status().aliyunToken().attempts()).isZero();
            assertThat(services.speech(SpeechMode.LOCAL_TEST_TONE,"anything")).isNotNull();
        } finally { services.close(); }
    }
    private record Resource(ProviderServices value) implements AutoCloseable { public void close() { value.close(); } }
}
