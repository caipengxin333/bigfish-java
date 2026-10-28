package cn.bigfish;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BigfishApplication {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(System.getenv().getOrDefault("BIGFISH_DATA_DIR", "./runtime")));
        SpringApplication.run(BigfishApplication.class, args);
    }
}
