package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.cli.LoadTestCli;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Warm-up phase, open (arrival-rate) model, production profile and session replay with real k6 against a service
 * that is slow and failing while cold.
 */
class K6TrafficE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static String pristine;
    private static HttpServer server;
    private static volatile long coldUntil;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final List<String> SEQUENCE = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicLong IDS = new AtomicLong(10);

    @BeforeAll
    static void generateAndServe() throws IOException {
        Path project = dir.resolve("shop");
        Path src = Files.createDirectories(project.resolve("src/main/java/shop"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/shop\n");
        Files.writeString(src.resolve("ItemController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class ItemController {
                    @GetMapping("/items/{id}") Object get(@PathVariable Long id) { return null; }
                    @PostMapping("/items") Object create(@RequestBody NewItem n) { return null; }
                    @GetMapping("/admin/report") Object report() { return null; }
                }
                record NewItem(String name, int quantity) { }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        ((ObjectNode) c.path("thinkTime")).put("min", 0.1).put("max", 0.1); // so a cold start is a visible share of the requests
        ((ObjectNode) c.path("defaults")).put("p95Ms", 200);
        ((ObjectNode) c.path("thresholds")).putArray("http_req_duration").add("p(95)<200");
        ((ObjectNode) c.path("thresholds")).putArray("http_req_failed").add("rate<0.01");
        pristine = c.toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(32));
        server.createContext("/shop/", K6TrafficE2ETest::handle);
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() throws IOException {
        Files.writeString(suite.resolve("loadtest.config.json"), pristine);
        Files.writeString(suite.resolve("data/traffic.json"), "{}");
        HITS.clear();
        SEQUENCE.clear();
        coldUntil = 0;
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceFirst("^/shop", "").replaceAll("/\\d+$", "/{n}");
        String route = ex.getRequestMethod() + " " + path;
        HITS.computeIfAbsent(route, k -> new AtomicInteger()).incrementAndGet();
        SEQUENCE.add(route);
        ex.getRequestBody().readAllBytes();
        boolean cold = System.currentTimeMillis() < coldUntil;
        if (cold) {
            try {
                Thread.sleep(400); // a cold JVM: slow, and the pool is not up yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = ("{\"id\":" + IDS.incrementAndGet() + "}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(cold ? 503 : ex.getRequestMethod().equals("POST") ? 201 : 200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static LoadTestRunner runner(String mode) {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        return LoadTestRunner.suite(suite).mode(mode).env("SEED", "false").env("VALIDATE_RESPONSES", "off")
                .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop");
    }

    @Test
    void theWarmUpIsLeftOutOfTheVerdict() {
        // the first seconds are slow and failing; the warm-up (30 s x 0.05 = 1.5 s) runs inside them, measuring
        // starts 5 s after it ends
        coldUntil = System.currentTimeMillis() + 6000;
        LoadTestRunner.RunResult warm = runner("mixed-load").env("DURATION_SCALE", "0.05").env("VUS", "4")
                .env("API", "getItem,createItem").output(l -> { }).run();
        assertThat(warm.passed()).as("cold requests were tagged warmup and ignored by the thresholds: " + warm.output()).isTrue();
        assertThat(warm.output()).doesNotContain("warmup_");

        coldUntil = System.currentTimeMillis() + 6000;
        LoadTestRunner.RunResult cold = runner("mixed-load").env("DURATION_SCALE", "0.05").env("VUS", "4")
                .env("API", "getItem,createItem").env("WARMUP", "off").output(l -> { }).run();
        assertThat(cold.passed()).as("without a warm-up the same cold start fails the run").isFalse();
    }

    @Test
    void theOpenModelArrivesOnScheduleWhateverTheResponseTime() {
        LoadTestRunner.RunResult r = runner("mixed-load").env("DURATION_SCALE", "0.05").env("MODEL", "open")
                .env("RATE", "20").env("WARMUP", "off").env("API", "getItem,createItem").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(r.output()).contains("iterations/s");
        int total = HITS.values().stream().mapToInt(AtomicInteger::get).sum();
        // 3 s ramp up + 15 s at 20/s + 3 s ramp down ≈ 360 arrivals
        assertThat(total).isBetween(250, 450);
    }

    private static String clf(int user, int second, String method, String path, int status) {
        return String.format("10.0.0.%d - u%d [10/Oct/2026:13:%02d:%02d +0000] \"%s /shop%s HTTP/1.1\" %d 100 \"-\" \"k6\" 0.010",
                user, user, second / 60, second % 60, method, path, status);
    }

    @Test
    void productionTrafficBecomesMixRateAndSessions() throws IOException {
        // 20 users, each: view an item, create one, view again; the report endpoint is never called; ~1 request/s
        List<String> log = new ArrayList<>();
        for (int u = 0; u < 20; u++) {
            log.add(clf(u, u * 25, "GET", "/items/" + (100 + u), 200));
            log.add(clf(u, u * 25 + 4, "POST", "/items", 201));
            log.add(clf(u, u * 25 + 8, "GET", "/items/" + (200 + u), 200));
        }
        Path file = dir.resolve("access.log");
        Files.write(file, log);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new LoadTestCli(new PrintStream(out), new PrintStream(out), System.in)
                .execute(new String[]{"traffic", "--suite", suite.toString(), "--access-log", file.toString()});
        assertThat(code).as(out.toString()).isZero();
        JsonNode c = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        assertThat(c.path("apis").path("report").path("weight").asInt()).isZero();
        assertThat(c.path("apis").path("getItem").path("weight").asInt()).isEqualTo(667);
        assertThat(c.path("modes").path("production").path("baseRate").asInt()).isGreaterThanOrEqualTo(1);

        // mixed-production: only what production called, open model, warm-up first
        LoadTestRunner.RunResult prod = runner("mixed-production").env("DURATION_SCALE", "0.02").output(l -> { }).run();
        assertThat(prod.passed()).as(prod.output()).isTrue();
        assertThat(HITS).as("the endpoint production never calls is not part of the mix").doesNotContainKey("GET /admin/report");
        assertThat(HITS).containsKeys("GET /items/{n}", "POST /items");

        // session-smoke: walks the observed transitions (get -> create -> get -> end)
        HITS.clear();
        SEQUENCE.clear();
        LoadTestRunner.RunResult session = runner("session-smoke").env("ITERATIONS", "10").output(l -> { }).run();
        assertThat(session.passed()).as(session.output()).isTrue();
        assertThat(SEQUENCE).isNotEmpty().first().isEqualTo("GET /items/{n}");
        assertThat(HITS).doesNotContainKey("GET /admin/report");
        // a create is only ever followed by a view, as in the log
        for (int i = 0; i < SEQUENCE.size() - 1; i++) {
            if (SEQUENCE.get(i).equals("POST /items")) {
                assertThat(SEQUENCE.get(i + 1)).isEqualTo("GET /items/{n}");
            }
        }
    }
}
