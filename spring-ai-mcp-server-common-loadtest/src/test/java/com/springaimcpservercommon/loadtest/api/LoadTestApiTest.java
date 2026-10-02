package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The library API: generate, run, read reports, compare with a baseline. */
class LoadTestApiTest {

    @TempDir
    Path dir;

    @Test
    void discoverAndGenerateThroughTheBuilder() {
        List<String> log = new ArrayList<>();
        LoadTestGenerator generator = LoadTestGenerator.builder()
                .project(Fixtures.sampleCrm())
                .outDir(dir.resolve("suite"))
                .noDatabase()
                .exclude("/rest/**")
                .value("CreateDealRequest.title", List.of("Renewal 2027"))
                .log(log::add)
                .build();

        LoadTestGenerator.DiscoveryResult d = generator.discover();
        assertThat(d.catalog().endpoints()).noneMatch(e -> e.path().startsWith("/rest/"));
        assertThat(d.discovered()).isGreaterThan(d.catalog().endpoints().size());
        assertThat(d.seed().steps()).extracting(s -> s.table()).contains("companies", "contacts", "deals");
        assertThat(d.basePath()).isEqualTo("/crm");

        LoadTestGenerator.GenerationResult r = generator.generate();
        assertThat(r.outDir()).isEqualTo(dir.resolve("suite"));
        assertThat(r.baseUrl()).isEqualTo("http://localhost:8090/crm");
        assertThat(r.apis()).isEqualTo(d.catalog().endpoints().size());
        assertThat(dir.resolve("suite/main.js")).exists();
        assertThat(dir.resolve("suite/data/seed.json")).exists();
        assertThat(log).anyMatch(l -> l.contains("APIs selected"));
    }

    @Test
    void theBuilderRefusesNothingToReadAndBadValues() {
        assertThatThrownBy(() -> LoadTestGenerator.builder().build().discover())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadTestGenerator.builder().project(dir.resolve("missing")).build().discover())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadTestGenerator.builder().sampleSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadTestRunner.suite(dir).mode("nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadTestRunner.suite(dir).run()).hasMessageContaining("main.js");
    }

    @Test
    void runsK6AndReadsTheReport() throws IOException {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ex.getRequestMethod().equals("POST") ? 202 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            Path suite = dir.resolve("suite");
            LoadTestGenerator.builder().project(Fixtures.sampleCrm()).outDir(suite).noDatabase().build().generate();
            List<String> lines = new ArrayList<>();
            LoadTestRunner.RunResult r = LoadTestRunner.suite(suite)
                    .mode("smoke")
                    .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/crm")
                    .env("API", "getHealthPing,version,info")
                    .env("SEED", "false")
                    .output(lines::add)
                    .run();
            assertThat(r.passed()).as(r.output()).isTrue();
            assertThat(lines).anyMatch(l -> l.contains("All thresholds passed"));
            assertThat(r.testId()).startsWith("smoke-");
            LoadTestReport report = r.report().orElseThrow();
            assertThat(report.mode()).isEqualTo("smoke");
            assertThat(report.thresholdsPassed()).isTrue();
            assertThat(report.total().requests()).isEqualTo(hits.get()).isEqualTo(9); // 3 APIs × 3 iterations
            assertThat(report.api("getHealthPing")).hasValueSatisfying(a -> {
                assertThat(a.requests()).isEqualTo(3);
                assertThat(a.failedRate()).isZero();
                assertThat(a.p95Ms()).isNotNull();
            });
            assertThat(LoadTestReport.latest(suite, "smoke")).map(LoadTestReport::file).contains(report.file());
            assertThat(LoadTestReport.latest(suite, "mixed-smoke")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private Path report(String name, String apis) throws IOException {
        Path f = Files.createDirectories(dir.resolve("reports")).resolve(name);
        Files.writeString(f, """
                {"mode":"mixed-load","dataMode":"auto","baseUrl":"http://x","apis":[%s],"failedThresholds":[],
                 "metrics":{"http_reqs":{"values":{"count":300,"rate":10}},
                            "http_req_failed":{"values":{"rate":0.01}},
                            "http_req_duration":{"values":{"p(95)":120.5}}}}
                """.formatted(apis));
        return f;
    }

    private static String api(String id, long requests, double failed, double p95) {
        return """
                {"api":"%s","name":"GET /%s","requests":%d,"rps":5,"failed":%s,"avg":10,"p95":%s,"p99":%s,"max":900}
                """.formatted(id, id, requests, failed, p95, p95 * 1.5);
    }

    @Test
    void comparisonFlagsRealRegressionsOnly() throws IOException {
        LoadTestReport base = LoadTestReport.read(report("mixed-load-2026-10-01T10-00-00-000Z.json", String.join(",",
                api("listOrders", 100, 0, 100), api("getOrder", 100, 0, 4), api("createOrder", 100, 0, 200),
                api("search", 100, 0.10, 300), api("rare", 3, 0, 10), api("gone", 50, 0, 10))));
        LoadTestReport now = LoadTestReport.read(report("mixed-load-2026-10-02T10-00-00-000Z.json", String.join(",",
                api("listOrders", 120, 0, 150),   // +50%: regression
                api("getOrder", 120, 0, 7),       // +75% but only +3 ms: noise
                api("createOrder", 120, 0.05, 210), // errors 0% → 5%: regression
                api("search", 120, 0, 120),       // faster and no errors: improved
                api("rare", 3, 0, 900),           // too few requests to judge
                api("added", 50, 0, 10))));
        assertThat(now.total().requests()).isEqualTo(300);
        assertThat(now.total().p95Ms()).isEqualTo(120.5);

        ReportComparison c = ReportComparison.compare(base, now, ReportComparison.Rules.DEFAULTS);
        assertThat(c.passed()).isFalse();
        assertThat(c.regressions()).extracting(ReportComparison.Change::api)
                .containsExactly("listOrders", "createOrder");
        assertThat(c.changes()).extracting(ReportComparison.Change::api, ReportComparison.Change::status)
                .contains(org.assertj.core.groups.Tuple.tuple("getOrder", "ok"),
                        org.assertj.core.groups.Tuple.tuple("search", "improved"),
                        org.assertj.core.groups.Tuple.tuple("rare", "too-few-requests"),
                        org.assertj.core.groups.Tuple.tuple("added", "new"),
                        org.assertj.core.groups.Tuple.tuple("gone", "missing"));
        assertThat(c.toMarkdown()).contains("| listOrders | 100.0 | 150.0 |").contains("**regression**")
                .contains("2 API(s) regressed.");
        assertThat(ReportComparison.compare(base, base, ReportComparison.Rules.DEFAULTS).passed()).isTrue();

        // newest first, by timestamp
        assertThat(LoadTestReport.reports(dir, "mixed-load")).extracting(p -> p.getFileName().toString())
                .containsExactly("mixed-load-2026-10-02T10-00-00-000Z.json", "mixed-load-2026-10-01T10-00-00-000Z.json");
        assertThatThrownBy(() -> LoadTestReport.read(Files.writeString(dir.resolve("x.json"), "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
