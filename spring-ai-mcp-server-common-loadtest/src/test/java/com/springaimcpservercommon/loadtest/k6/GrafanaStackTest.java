package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The suite's Grafana stack: scrape target, files written once vs regenerated, dashboard, run annotations. */
class GrafanaStackTest {

    @TempDir
    Path dir;

    @Test
    void scrapeTargetFollowsTheBaseUrlAndManagementSettings() {
        assertThat(GrafanaStack.scrape("http://localhost:8090/crm", Map.of()))
                .isEqualTo(new GrafanaStack.Scrape("http", "host.docker.internal:8090", "/crm/actuator/prometheus"));
        assertThat(GrafanaStack.scrape("https://shop.test.internal/", Map.of()))
                .isEqualTo(new GrafanaStack.Scrape("https", "shop.test.internal:443", "/actuator/prometheus"));
        // a separate management server has no servlet context path
        assertThat(GrafanaStack.scrape("http://127.0.0.1:8080/shop", Map.of("management.server.port", "9091",
                "management.endpoints.web.base-path", "/manage/")))
                .isEqualTo(new GrafanaStack.Scrape("http", "host.docker.internal:9091", "/manage/prometheus"));
        assertThat(GrafanaStack.scrape("http://localhost:8080/shop", Map.of("management.server.port", "8080")))
                .extracting(GrafanaStack.Scrape::metricsPath).isEqualTo("/shop/actuator/prometheus");
    }

    @Test
    void teamFilesAreWrittenOnceAndTheDashboardIsRegenerated() throws IOException {
        Path suite = dir.resolve("suite");
        GrafanaStack.write(suite, "Sample \"CRM\"", new GrafanaStack.Scrape("http", "host.docker.internal:8090",
                "/crm/actuator/prometheus"));
        Path prometheus = suite.resolve("grafana/prometheus.yml");
        assertThat(Files.readString(prometheus)).contains("host.docker.internal:8090")
                .contains("metrics_path: /crm/actuator/prometheus");
        assertThat(Files.readString(suite.resolve("grafana/docker-compose.yml")))
                .contains("name: loadtest-sample-crm").contains("--web.enable-remote-write-receiver")
                .contains("127.0.0.1:3000:3000");
        JsonNode dashboard = Documents.parse(Files.readString(suite.resolve("grafana/dashboards/k6-load-test.json")));
        assertThat(dashboard.path("uid").asString()).isEqualTo("k6-sample-crm");
        assertThat(dashboard.path("title").asString()).isEqualTo("Load test — Sample \"CRM\"");
        List<String> exprs = new ArrayList<>();
        dashboard.path("panels").forEach(p -> p.path("targets").forEach(t -> exprs.add(t.path("expr").asString())));
        assertThat(exprs).anyMatch(e -> e.contains("k6_http_req_duration_p95") && e.contains("api=~\"$api\""))
                .anyMatch(e -> e.contains("http_server_requests_seconds_count"))
                .anyMatch(e -> e.contains("hikaricp_connections_pending"));
        assertThat(dashboard.path("panels")).anyMatch(p -> "api".equals(p.path("repeat").asString(null)));

        Files.writeString(prometheus, "# edited by the team\n");
        GrafanaStack.write(suite, "renamed", new GrafanaStack.Scrape("http", "other:1", "/x"));
        assertThat(Files.readString(prometheus)).isEqualTo("# edited by the team\n");
        assertThat(Files.readString(suite.resolve("grafana/dashboards/k6-load-test.json"))).contains("renamed");
    }

    @Test
    void generatedSuitesCarryTheStackAndTheRunSwitch() throws IOException {
        Path suite = dir.resolve("crm");
        LoadTestGenerator.builder().project(Fixtures.sampleCrm()).outDir(suite).noDatabase().build().generate();
        assertThat(Files.readString(suite.resolve("grafana/prometheus.yml")))
                .contains("host.docker.internal:8090").contains("/crm/actuator/prometheus");
        assertThat(Files.readString(suite.resolve("run.sh"))).contains("GRAFANA:-")
                .contains("experimental-prometheus-rw").contains("testid=$TEST_ID");
        assertThat(Files.readString(suite.resolve("README.md"))).contains("## Grafana dashboard");
        assertThat(suite.resolve("lib/grafana.js")).exists();
    }

    @Test
    void runsAreAnnotatedOnGrafana() throws IOException {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        List<String> grafanaCalls = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/annotations", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            grafanaCalls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"id\":42,\"message\":\"Annotation added\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/", ex -> {
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            Path suite = dir.resolve("crm");
            LoadTestGenerator.builder().project(Fixtures.sampleCrm()).outDir(suite).noDatabase().build().generate();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            LoadTestRunner.RunResult r = LoadTestRunner.suite(suite).mode("smoke")
                    .env("BASE_URL", url + "/crm").env("API", "getHealthPing").env("SEED", "false")
                    .grafanaAnnotations(url, "svc-token")
                    .output(line -> { })
                    .run();
            assertThat(r.passed()).as(r.output()).isTrue();
            assertThat(grafanaCalls).hasSize(2);
            assertThat(grafanaCalls.get(0)).startsWith("POST /api/annotations ").contains("\"k6\"")
                    .contains("\"smoke\"").contains(r.testId()).contains("auth=Bearer svc-token");
            assertThat(grafanaCalls.get(1)).startsWith("PATCH /api/annotations/42 ").contains("timeEnd");
            // annotation calls are not an API of the suite
            assertThat(r.report().orElseThrow().apis()).extracting(a -> a.api()).containsExactly("getHealthPing");
        } finally {
            server.stop(0);
        }
    }
}
