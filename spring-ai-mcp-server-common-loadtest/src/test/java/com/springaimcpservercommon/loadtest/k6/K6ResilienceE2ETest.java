package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
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
 * Resilience experiments against a fake Toxiproxy server and a service that behaves the way a proxied one would: it fails
 * while a toxic is on. Proves the faults are injected on schedule, removed afterwards, that requests during a fault are
 * judged on their own (the run-wide thresholds ignore them) and that a service which does not recover fails the run.
 */
class K6ResilienceE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer app;
    private static HttpServer toxiproxy;
    /** What the fake Toxiproxy saw: "POST /proxies/app/toxics", "DELETE …". */
    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> OUTPUT = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean faulty;
    private static volatile boolean stuck;

    @BeforeAll
    static void generateAndServe() throws IOException {
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

        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/shop/ping", ex -> reply(ex, faulty || stuck ? 503 : 200, "{\"ok\":true}"));
        app.start();
        toxiproxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toxiproxy.createContext("/", K6ResilienceE2ETest::toxiproxyApi);
        toxiproxy.start();
    }

    @AfterAll
    static void stop() {
        app.stop(0);
        toxiproxy.stop(0);
    }

    @BeforeEach
    void reset() {
        faulty = false;
        stuck = false;
        CALLS.clear();
        OUTPUT.clear();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    /** The slice of Toxiproxy's REST API the suite uses; a toxic makes the app fail, removing it heals (unless stuck). */
    private static void toxiproxyApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        ex.getRequestBody().readAllBytes();
        CALLS.add(method + " " + path);
        if (path.equals("/version")) {
            reply(ex, 200, "2.9.0");
        } else if (method.equals("POST") && path.equals("/proxies")) {
            reply(ex, 201, "{}");
        } else if (method.equals("POST") && path.endsWith("/toxics")) {
            faulty = true;
            reply(ex, 200, "{}");
        } else if (method.equals("DELETE") && path.contains("/toxics/")) {
            faulty = false;
            stuck = K6ResilienceE2ETest.stuckAfterFault;
            ex.sendResponseHeaders(204, -1);
            ex.close();
        } else if (path.equals("/reset")) {
            faulty = false;
            stuck = false;
            reply(ex, 200, "{}");
        } else {
            reply(ex, 200, "{}");
        }
    }

    private static volatile boolean stuckAfterFault;

    /** Rewrites the suite config: a short constant load and one experiment (6 s in, 6 s long, 6 s of recovery). */
    private static void configure(String expect) throws IOException {
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("thinkTime")).put("min", 0.2).put("max", 0.2);
        ObjectNode load = ((ObjectNode) c.path("modes")).putObject("load");
        load.put("executor", "constant-vus").put("vus", 2).put("duration", "22s");
        ObjectNode r = c.putObject("resilience");
        r.put("enabled", false).put("toxiproxy", "http://127.0.0.1:" + toxiproxy.getAddress().getPort());
        r.putArray("proxies").addObject().put("name", "app").put("listen", "0.0.0.0:8666").put("upstream", "localhost:8080");
        ObjectNode e = r.putArray("experiments").addObject();
        e.put("name", "blip").put("proxy", "app").put("startAfter", "6s").put("duration", "6s").put("recovery", "6s");
        e.putArray("toxics").addObject().put("type", "latency").put("stream", "downstream")
                .set("attributes", Documents.json().createObjectNode().put("latency", 300));
        e.set("expect", Documents.parse(expect));
        Files.writeString(config, c.toString());
    }

    private static LoadTestRunner runner() {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        return LoadTestRunner.suite(suite).mode("mixed-load").env("SEED", "false").env("VALIDATE_RESPONSES", "off")
                .env("BASE_URL", "http://127.0.0.1:" + app.getAddress().getPort() + "/shop")
                .resilience("http://127.0.0.1:" + toxiproxy.getAddress().getPort(), null).output(OUTPUT::add);
    }

    private static String output() {
        return String.join("\n", OUTPUT);
    }

    @Test
    void faultsAreInjectedOnScheduleJudgedOnTheirOwnAndRemoved() throws IOException {
        configure("{\"maxErrorRate\":1.01}");
        stuckAfterFault = false;
        LoadTestRunner.RunResult r = runner().run();
        assertThat(r.passed()).as("errors during the fault must not fail the run\n" + output()).isTrue();
        assertThat(CALLS).contains("GET /version", "POST /proxies").anyMatch(c -> c.matches("POST /proxies/app/toxics"))
                .anyMatch(c -> c.startsWith("DELETE /proxies/app/toxics/blip-latency-0")).endsWith("POST /reset");
        assertThat(output()).contains("resilience: blip ON for 6s (latency on app)").contains("resilience: blip OFF")
                .contains("Resilience (faults injected through Toxiproxy):");
        JsonNode report = Documents.parse(Files.readString(r.report().orElseThrow().file()));
        JsonNode blip = report.path("resilience").get(0);
        assertThat(blip.path("experiment").asString()).isEqualTo("blip");
        assertThat(blip.path("during").path("requests").asInt()).as("requests under the fault").isGreaterThan(5);
        assertThat(blip.path("during").path("failed").asDouble()).as("the service failed under the fault").isGreaterThan(0.9);
        assertThat(blip.path("after").path("requests").asInt()).isGreaterThan(5);
        assertThat(blip.path("after").path("failed").asDouble()).as("and recovered").isZero();
        assertThat(blip.path("during").path("ok").asBoolean()).isTrue();
    }

    @Test
    void aServiceThatDoesNotRecoverFailsTheRun() throws IOException {
        configure("{\"maxErrorRate\":1.01}");
        stuckAfterFault = true;
        LoadTestRunner.RunResult r = runner().run();
        assertThat(r.thresholdsFailed()).as(output()).isTrue();
        assertThat(output()).contains("http_req_failed{fault:recover_blip}").contains("FAIL");
    }

    @Test
    void theErrorRateAllowedDuringAFaultIsEnforced() throws IOException {
        configure("{\"maxErrorRate\":0.1}");
        stuckAfterFault = false;
        LoadTestRunner.RunResult r = runner().run();
        assertThat(r.thresholdsFailed()).as(output()).isTrue();
        assertThat(output()).contains("http_req_failed{fault:blip}");
    }

    @Test
    void anUnreachableToxiproxyStopsTheRunBeforeAnyLoad() {
        LoadTestRunner.RunResult r = LoadTestRunner.suite(suite).mode("mixed-load").env("SEED", "false")
                .resilience("http://127.0.0.1:1", null).output(OUTPUT::add).run();
        assertThat(r.exitCode()).isEqualTo(LoadTestRunner.RESILIENCE_UNAVAILABLE);
        assertThat(output()).contains("Toxiproxy is not reachable").contains("resilience-init");
    }
}
