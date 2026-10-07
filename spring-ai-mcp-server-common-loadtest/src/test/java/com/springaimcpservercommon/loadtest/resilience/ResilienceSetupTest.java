package com.springaimcpservercommon.loadtest.resilience;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.cli.LoadTestCli;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The resilience setup written into a suite, the Toxiproxy client, and the {@code resilience-init} command. */
class ResilienceSetupTest {

    @TempDir
    Path dir;

    private Path suite() throws IOException {
        Path project = Files.createDirectories(dir.resolve("shop/src/main/java/shop"));
        Files.createDirectories(dir.resolve("shop/src/main/resources"));
        Files.writeString(dir.resolve("shop/src/main/resources/application.properties"),
                "server.port=9090\nserver.servlet.context-path=/shop\n");
        Files.writeString(project.resolve("PingController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class PingController {
                    @GetMapping("/ping") Object ping() { return null; }
                }
                """);
        Path suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(dir.resolve("shop")).outDir(suite).noDatabase().build().generate();
        return suite;
    }

    @Test
    void theDefaultBlockProxiesTheApplicationAndTheDependencies() {
        JsonNode block = ResilienceSetup.block(new ResilienceSetup.Options("http://localhost:9090/shop", null, 8666,
                List.of(new ResilienceSetup.Dependency("db", "host.docker.internal:5432", 15432)),
                "http://localhost:8474", "img"));
        assertThat(block.path("enabled").asBoolean()).as("experiments only run when asked for").isFalse();
        assertThat(block.path("baseUrl").asString()).isEqualTo("http://localhost:8666/shop");
        assertThat(block.path("proxies").get(0).path("upstream").asString())
                .as("a local application is reached from the container through the host").isEqualTo("host.docker.internal:9090");
        assertThat(block.path("proxies").get(1).path("listen").asString()).isEqualTo("0.0.0.0:15432");
        List<String> names = new ArrayList<>();
        block.path("experiments").forEach(e -> names.add(e.path("name").asString()));
        assertThat(names).containsExactly("slow-network", "narrow-bandwidth", "connection-resets", "outage",
                "db-latency", "db-outage");
        JsonNode outage = block.path("experiments").get(3);
        assertThat(outage.path("toxics").get(0).path("type").asString()).isEqualTo("down");
        assertThat(outage.path("expect").path("maxErrorRate").asDouble()).isEqualTo(1.0);
        // experiments follow one another: none starts before the previous one has recovered
        long previousEnd = 0;
        for (JsonNode e : block.path("experiments")) {
            long start = Long.parseLong(e.path("startAfter").asString().replace("s", ""));
            assertThat(start).isGreaterThanOrEqualTo(previousEnd);
            previousEnd = start + Long.parseLong(e.path("duration").asString().replace("s", ""))
                    + Long.parseLong(e.path("recovery").asString().replace("s", ""));
        }
    }

    @Test
    void anApplicationOnAnotherHostIsProxiedAsIs() {
        assertThat(ResilienceSetup.block(ResilienceSetup.Options.of("https://staging.example.com/api"))
                .path("proxies").get(0).path("upstream").asString()).isEqualTo("staging.example.com:443");
    }

    @Test
    void theComposeFilePublishesTheProxyPortsAndReachesTheHost() {
        String yaml = ResilienceSetup.compose(new ResilienceSetup.Options("http://localhost:9090", null, 8666,
                List.of(new ResilienceSetup.Dependency("db", "host.docker.internal:5432", 15432)),
                "http://localhost:8474", "ghcr.io/shopify/toxiproxy:2.9.0"));
        assertThat(yaml).contains("image: ghcr.io/shopify/toxiproxy:2.9.0").contains("\"8474:8474\"")
                .contains("\"8666:8666\"").contains("\"15432:15432\"").contains("host.docker.internal:host-gateway");
    }

    @Test
    void writeAddsTheBlockAndFilesAndRefusesToOverwriteEdits() throws IOException {
        Path suite = suite();
        Path compose = ResilienceSetup.write(suite, ResilienceSetup.Options.of(""), false);
        assertThat(compose).exists();
        assertThat(suite.resolve("resilience/toxiproxy.json")).content().contains("\"name\" : \"app\"");
        JsonNode config = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        assertThat(config.path("resilience").path("baseUrl").asString()).as("the suite's own base URL, proxied")
                .isEqualTo("http://localhost:8666/shop");
        assertThat(config.path("resilience").path("proxies").get(0).path("upstream").asString())
                .isEqualTo("host.docker.internal:9090");
        assertThatThrownBy(() -> ResilienceSetup.write(suite, ResilienceSetup.Options.of(""), false))
                .hasMessageContaining("--force");
        ResilienceSetup.write(suite, ResilienceSetup.Options.of(""), true);
    }

    @Test
    void regeneratingTheSuiteKeepsTheExperiments() throws IOException {
        Path suite = suite();
        ResilienceSetup.write(suite, ResilienceSetup.Options.of(""), false);
        Path config = suite.resolve("loadtest.config.json");
        Files.writeString(config, Files.readString(config).replace("slow-network", "my-experiment"));
        LoadTestGenerator.builder().project(dir.resolve("shop")).outDir(suite).noDatabase().build().generate();
        assertThat(config).content().contains("my-experiment").contains("\"resilience\"");
    }

    @Test
    void theCliWritesTheSetupAndParsesDependencies() throws IOException {
        Path suite = suite();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new LoadTestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]))
                .execute(new String[]{"resilience-init", "--suite", suite.toString(), "--listen-port", "18666",
                        "--dependency", "db=host.docker.internal:5432:15432"});
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("docker compose -f").contains("--resilience");
        assertThat(suite.resolve("loadtest.config.json")).content().contains("http://localhost:18666/shop")
                .contains("db-outage");
        int bad = new LoadTestCli(new PrintStream(out), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0])).execute(new String[]{"resilience-init", "--suite", suite.toString(),
                "--force", "--dependency", "nonsense"});
        assertThat(bad).isNotZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--dependency expects name=");
    }

    @Test
    void theClientReadsVersionAndProxiesAndResets() throws IOException {
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            String body = ex.getRequestURI().getPath().equals("/version") ? "2.9.0\n"
                    : ex.getRequestURI().getPath().equals("/proxies") ? "{\"app\":{},\"db\":{}}" : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            Toxiproxy client = new Toxiproxy("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertThat(client.version()).isEqualTo("2.9.0");
            assertThat(client.proxies()).containsExactly("app", "db");
            client.reset();
            assertThat(calls).contains("POST /reset");
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(() -> new Toxiproxy("http://127.0.0.1:1").version()).isInstanceOf(IOException.class);
    }
}
