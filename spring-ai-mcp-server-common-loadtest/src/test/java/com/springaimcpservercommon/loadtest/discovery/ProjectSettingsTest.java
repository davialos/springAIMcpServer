package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSettingsTest {

    @Test
    void readsYamlWithPlaceholdersResolvedFromEnvironmentOrDefault() {
        Map<String, String> env = Map.of("SHOP_DB_URL", "jdbc:postgresql://db:5432/shop");
        ProjectSettings s = ProjectSettings.read(Fixtures.sampleShop(), env::get);
        assertThat(s.serverPort()).isEqualTo(8081);
        assertThat(s.contextPath()).isEqualTo("/shop");
        assertThat(s.localBaseUrl()).isEqualTo("http://localhost:8081/shop");
        assertThat(s.datasourceUrl()).isEqualTo("jdbc:postgresql://db:5432/shop");
        assertThat(s.datasourceUsername()).isEqualTo("shop");
    }

    @Test
    void readsPropertiesAndSnakeCaseNaming(@TempDir Path dir) throws IOException {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.properties"), """
                server.servlet.context-path=/api-root/
                spring.jackson.property-naming-strategy=SNAKE_CASE
                spring.datasource.url=${DB_URL}
                """);
        ProjectSettings s = ProjectSettings.read(dir, k -> null);
        assertThat(s.contextPath()).isEqualTo("/api-root");
        assertThat(s.snakeCaseJson()).isTrue();
        assertThat(s.datasourceUrl()).isNull(); // unresolvable placeholder: no guess
        assertThat(ProjectSettings.read(dir.resolve("missing"))).isEqualTo(ProjectSettings.DEFAULTS);
    }
}
