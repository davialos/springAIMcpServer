package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Faults injected by a real Toxiproxy container into a service running on the host: a latency toxic really slows the
 * answers down while it is on, an outage really makes them fail, and both are gone afterwards. Needs Docker and k6;
 * {@code TOXIPROXY_IMAGE} overrides the image (default {@code ghcr.io/shopify/toxiproxy:2.9.0}).
 */
class K6ToxiproxyIT {

    private static final int PROXY_PORT = 8666;

    @TempDir
    static Path dir;
    private static HttpServer app;
    private static GenericContainer<?> toxiproxy;
    private static Path suite;

    @BeforeAll
    static void start() throws IOException {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        assumeThat(DockerClientFactory.instance().isDockerAvailable()).as("Docker").isTrue();
        Path project = dir.resolve("shop");
        Path src = Files.createDirectories(project.resolve("src/main/java/shop"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/shop\n");
        Files.writeString(src.resolve("PingController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class PingController {
                    @GetMapping("/ping") Object ping() { return null; }
                }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();

        // reachable from the container as host.docker.internal
        app = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        app.createContext("/shop/ping", ex -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        app.start();
        try {
            toxiproxy = new GenericContainer<>(System.getenv().getOrDefault("TOXIPROXY_IMAGE", "ghcr.io/shopify/toxiproxy:2.9.0"))
                    .withExposedPorts(8474, PROXY_PORT)
                    .withExtraHost("host.docker.internal", "host-gateway")
                    .waitingFor(Wait.forHttp("/version").forPort(8474));
            toxiproxy.start();
        } catch (RuntimeException e) {
            toxiproxy = null;
            assumeThat(e).as("the Toxiproxy image could not be started: " + e.getMessage()).isNull();
        }
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop(0);
        }
        if (toxiproxy != null) {
            toxiproxy.stop();
        }
    }

    @Test
    void latencyAndOutageAreRealAndRemoved() throws IOException {
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("thinkTime")).put("min", 0.2).put("max", 0.2);
        ((ObjectNode) c.path("modes")).putObject("load").put("executor", "constant-vus").put("vus", 2).put("duration", "26s");
        ObjectNode r = c.putObject("resilience");
        r.put("enabled", false);
        r.putArray("proxies").addObject().put("name", "app").put("listen", "0.0.0.0:" + PROXY_PORT)
                .put("upstream", "host.docker.internal:" + app.getAddress().getPort());
        var experiments = r.putArray("experiments");
        ObjectNode slow = experiments.addObject();
        slow.put("name", "slow").put("proxy", "app").put("startAfter", "5s").put("duration", "5s").put("recovery", "4s");
        slow.putArray("toxics").addObject().put("type", "latency").put("stream", "downstream")
                .set("attributes", Documents.json().createObjectNode().put("latency", 800));
        ObjectNode out = experiments.addObject();
        out.put("name", "out").put("proxy", "app").put("startAfter", "15s").put("duration", "4s").put("recovery", "3s");
        out.putArray("toxics").addObject().put("type", "down");
        Files.writeString(config, c.toString());

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        LoadTestRunner.RunResult run = LoadTestRunner.suite(suite).mode("mixed-load").env("SEED", "false")
                .env("VALIDATE_RESPONSES", "off")
                .env("BASE_URL", "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT) + "/shop")
                .resilience("http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8474), null)
                .output(output::add).run();
        assertThat(run.passed()).as(String.join("\n", output)).isTrue();

        JsonNode report = Documents.parse(Files.readString(run.report().orElseThrow().file())).path("resilience");
        JsonNode latency = report.get(0);
        assertThat(latency.path("during").path("p95").asDouble()).as("answers are slowed by the toxic").isGreaterThan(700);
        assertThat(latency.path("after").path("p95").asDouble()).as("and fast again afterwards").isLessThan(300);
        JsonNode outage = report.get(1);
        assertThat(outage.path("during").path("failed").asDouble()).as("the proxy is down").isGreaterThan(0.9);
        assertThat(outage.path("after").path("failed").asDouble()).as("and back").isZero();
    }
}
