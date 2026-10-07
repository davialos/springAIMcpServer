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
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Server-side checks from the target's own Prometheus endpoint: a load test whose requests all succeed still fails
 * when the service shows a database pool queue, GC taking half the time or logged errors.
 */
class K6ServerChecksE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static volatile String trouble = "none";
    private static final long START = System.currentTimeMillis();

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
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        ((ObjectNode) c.path("thinkTime")).put("min", 0.3).put("max", 0.3); // a run of a few seconds
        Files.writeString(suite.resolve("loadtest.config.json"), c.toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/ping", K6ServerChecksE2ETest::ping);
        server.createContext("/shop/actuator/prometheus", K6ServerChecksE2ETest::metrics);
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        trouble = "none";
    }

    private static void reply(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static void ping(HttpExchange ex) throws IOException {
        reply(ex, 200, "application/json", "{\"ok\":true}");
    }

    private static void metrics(HttpExchange ex) throws IOException {
        double seconds = (System.currentTimeMillis() - START) / 1000.0;
        StringBuilder m = new StringBuilder("""
                # HELP x
                http_server_requests_seconds_count{method="GET",status="200",uri="/ping"} 100
                jvm_threads_live_threads 30
                """);
        m.append("jvm_gc_pause_seconds_sum{action=\"end of minor GC\"} ")
                .append(trouble.equals("gc") ? seconds * 0.5 : seconds * 0.001).append('\n');
        m.append("hikaricp_connections_pending{pool=\"main\"} ").append(trouble.equals("pool") ? 6 : 0).append('\n');
        m.append("logback_events_total{level=\"error\"} ").append(trouble.equals("logs") ? (int) (seconds * 10) : 0).append('\n');
        reply(ex, 200, "text/plain; version=0.0.4", m.toString());
    }

    private static int run(String... extra) {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] base = {"run", "--suite", suite.toString(), "--mode", "smoke", "--base-url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/shop", "--server-interval", "1"};
        String[] args = new String[base.length + extra.length + 3];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        args[base.length + extra.length] = "--";
        args[base.length + extra.length + 1] = "-e";
        args[base.length + extra.length + 2] = "SEED=false";
        int code = new LoadTestCli(new PrintStream(out), new PrintStream(out), System.in).execute(args);
        lastOutput = out.toString();
        return code;
    }

    private static String lastOutput = "";

    @Test
    void aHealthyServerPassesAndTheChecksAreListed() {
        assertThat(run()).as(lastOutput).isZero();
        assertThat(lastOutput).contains("server checks: sampling").contains("Server-side checks passed.")
                .contains("ok   gc-share").contains("ok   hikari-pending");
    }

    @Test
    void aQueueForDatabaseConnectionsFailsTheRunEvenThoughEveryRequestSucceeded() throws IOException {
        trouble = "pool";
        assertThat(run()).as(lastOutput).isEqualTo(LoadTestRunner.SERVER_CHECKS_FAILED);
        assertThat(lastOutput).contains("All thresholds passed.").contains("FAIL hikari-pending")
                .contains("Server-side checks FAILED.");
        try (var files = Files.list(suite.resolve("reports"))) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.endsWith("-server-checks.json"));
        }
    }

    @Test
    void gcThatTakesHalfTheTimeAndLoggedErrorsFailToo() {
        trouble = "gc";
        assertThat(run()).as(lastOutput).isEqualTo(LoadTestRunner.SERVER_CHECKS_FAILED);
        assertThat(lastOutput).contains("FAIL gc-share");
        trouble = "logs";
        assertThat(run()).as(lastOutput).isEqualTo(LoadTestRunner.SERVER_CHECKS_FAILED);
        assertThat(lastOutput).contains("FAIL log-errors");
    }

    @Test
    void theChecksCanBeSwitchedOffAndAnUnreachableEndpointIsSkipped() {
        trouble = "pool";
        assertThat(run("--no-server-checks")).as(lastOutput).isZero();
        assertThat(lastOutput).doesNotContain("Server-side checks");
        assertThat(run("--server-checks", "http://127.0.0.1:1/nothing")).as(lastOutput).isZero();
        assertThat(lastOutput).contains("server checks: cannot read");
    }
}
