package cn.bigfish.core;

import cn.bigfish.configuration.AvatarAssetsConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"bigfish.avatar.assets-dir=", "BIGFISH_ALLOW_PAID=false"})
@AutoConfigureMockMvc
class AvatarAssetsDisabledTest {
    static final Path DATA;
    static { try { DATA = Files.createTempDirectory("bigfish-avatar-disabled-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("bigfish.data-dir", DATA::toString);
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db"));
    }
    @Autowired MockMvc mvc;

    @Test void unconfiguredServiceStartsWithMissingResourcesAndNoSession() throws Exception {
        mvc.perform(get("/avatar-assets/models/BigFish/BigFish.model3.json")).andExpect(status().isNotFound());
        mvc.perform(head("/avatar-assets/sdk/live2dcubismcore.min.js")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runtime")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));
    }

    @Test void explicitInvalidRootsFailClearlyWithoutDisclosingPathsOrFallingBack() throws Exception {
        Path file = Files.writeString(DATA.resolve("not-a-directory"), "synthetic");
        for (String root : List.of(DATA.resolve("missing").toString(), file.toString(), "relative/path", "/",
                System.getProperty("user.home"), Path.of("").toAbsolutePath().toString(), DATA.toString())) {
            assertThatThrownBy(() -> new AvatarAssetsConfiguration(root, DATA.toString()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContainingAll("AVATAR_ASSETS_DIR_INVALID", "BIGFISH_AVATAR_ASSETS_DIR")
                    .hasNoCause();
        }
        Path alias = Files.createSymbolicLink(DATA.resolve("runtime-link"), DATA);
        assertThatThrownBy(() -> new AvatarAssetsConfiguration(alias.toString(), DATA.toString()))
                .hasMessageContaining("AVATAR_ASSETS_DIR_INVALID").hasNoCause();
    }

    @Test void directoryWithoutSearchPermissionIsRejectedAtStartup() throws Exception {
        Path root = Files.createTempDirectory("bigfish-avatar-unsearchable-");
        var original = Files.getPosixFilePermissions(root);
        try {
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
            assertThat(Files.isReadable(root)).isTrue();
            assertThat(Files.isExecutable(root)).isFalse();
            assertThatThrownBy(() -> new AvatarAssetsConfiguration(root.toString(), DATA.toString()))
                    .hasMessageContaining("AVATAR_ASSETS_DIR_INVALID").hasNoCause();
        } finally { Files.setPosixFilePermissions(root, original); }
    }
}
