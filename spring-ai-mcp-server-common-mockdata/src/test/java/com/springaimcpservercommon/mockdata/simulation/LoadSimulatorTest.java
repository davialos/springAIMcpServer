package com.springaimcpservercommon.mockdata.simulation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadSimulatorTest {

    record Payload(String user, int qty) {
    }

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> contentTypes = new ConcurrentLinkedQueue<>();
    private volatile int delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api", ex -> {
            int n = hits.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes()));
            contentTypes.add(String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("mixed")
                    ? (n % 4 == 0 ? 500 : n % 4 == 1 ? 404 : 200) : 200;
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String query) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/api" + query);
    }

    private static LoadSimulator simulator() {
        return new LoadSimulator(new DefaultSimulationGuard(DefaultSimulationGuard.Limits.defaults(), () -> false));
    }

    @Test
    void sendsGeneratedPayloadsAsJsonFromManyVirtualUsersAndReportsLatency() {
        delayMillis = 20;
        AtomicInteger seq = new AtomicInteger();
        SimulationReport r = simulator().run(SimulationSpec.post(url(""), () -> new Payload("user" + seq.incrementAndGet(), 3), 20, Duration.ofSeconds(2)));
        assertThat(r.requests()).isEqualTo(hits.get()).isGreaterThan(100);
        assertThat(r.failures()).isZero();
        assertThat(r.outcomes()).containsOnlyKeys("2xx");
        assertThat(r.p50Micros()).isBetween(19_000L, 80_000L);
        assertThat(r.p99Micros()).isGreaterThanOrEqualTo(r.p50Micros());
        assertThat(r.maxMicros()).isGreaterThanOrEqualTo(r.p99Micros());
        assertThat(r.requestsPerSecond()).isGreaterThan(50);
        assertThat(bodies).allMatch(b -> b.matches("\\{\"user\":\"user\\d+\",\"qty\":3}"));
        assertThat(contentTypes).allMatch("application/json"::equals);
        assertThat(r.cancelled()).isFalse();
    }

    @Test
    void countsFailuresByKindAndNeverReportsData() {
        SimulationReport r = simulator().run(SimulationSpec.post(url("?mixed"), () -> new Payload("secret-user-name", 1), 4, Duration.ofSeconds(1)));
        assertThat(r.outcomes()).containsKeys("2xx", "4xx", "5xx");
        assertThat(r.failures()).isEqualTo(r.outcomes().get("4xx") + r.outcomes().get("5xx"));
        assertThat(r.successes() + r.failures()).isEqualTo(r.requests());
        assertThat(r.errorSamples()).containsExactlyInAnyOrder("HTTP 404", "HTTP 500");
        assertThat(r.toString()).doesNotContain("secret-user-name");
        assertThat(r.errorRate()).isBetween(0.3, 0.9);
    }

    @Test
    void connectionErrorsAndTimeoutsAreCountedNotSwallowed() throws Exception {
        int dead;
        try (var s = new java.net.ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        SimulationReport refused = simulator().run(SimulationSpec.post(URI.create("http://localhost:" + dead + "/x"), null, 2, Duration.ofMillis(500)));
        assertThat(refused.requests()).isPositive();
        assertThat(refused.failures()).isEqualTo(refused.requests());
        assertThat(refused.outcomes().keySet()).allMatch(k -> k.startsWith("error:"));

        delayMillis = 400;
        SimulationSpec slow = new SimulationSpec(url(""), "GET", Map.of(), null, 2, Duration.ofSeconds(1), Duration.ZERO, 0, Duration.ofMillis(100));
        SimulationReport timeouts = simulator().run(slow);
        assertThat(timeouts.outcomes()).containsKey("timeout");
        assertThat(timeouts.failures()).isEqualTo(timeouts.requests());
    }

    @Test
    void aPayloadSupplierThatThrowsIsAFailureNotACrash() {
        SimulationReport r = simulator().run(SimulationSpec.post(url(""), () -> {
            throw new IllegalStateException("boom");
        }, 2, Duration.ofMillis(300)));
        assertThat(r.outcomes()).containsKey("payload-error");
        assertThat(hits).hasValue(0);
    }

    @Test
    void honoursTheRequestRateCap() {
        SimulationSpec spec = new SimulationSpec(url(""), "GET", Map.of(), null, 10, Duration.ofSeconds(2), Duration.ZERO, 50, Duration.ofSeconds(5));
        SimulationReport r = simulator().run(spec);
        assertThat(r.requests()).as("50 req/s for 2 s").isBetween(70L, 130L);
    }

    @Test
    void rampUpStartsUsersGradually() {
        SimulationSpec spec = new SimulationSpec(url(""), "GET", Map.of(), null, 5, Duration.ofSeconds(1), Duration.ofMillis(800), 0, Duration.ofSeconds(5));
        SimulationReport flat = simulator().run(new SimulationSpec(url(""), "GET", Map.of(), null, 5, Duration.ofSeconds(1), Duration.ZERO, 0, Duration.ofSeconds(5)));
        int before = hits.get();
        hits.set(0);
        SimulationReport ramped = simulator().run(spec);
        assertThat(ramped.requests()).isLessThan(flat.requests());
        assertThat(before).isPositive();
    }

    @Test
    void canBeCancelledAndWatched() throws Exception {
        SimulationHandle h = simulator().start(SimulationSpec.post(url(""), () -> new Payload("u", 1), 5, Duration.ofSeconds(30)));
        Thread.sleep(300);
        assertThat(h.requestsSoFar()).isPositive();
        long t0 = System.nanoTime();
        h.cancel();
        SimulationReport r = h.await();
        assertThat(r.cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        assertThat(r.elapsed()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void theGuardRefusesWhatShouldNeverRun() {
        DefaultSimulationGuard.Limits limits = new DefaultSimulationGuard.Limits(List.of("api.test.example.com", "*.stage.example.com"), true, 50, Duration.ofMinutes(1), 100);
        LoadSimulator sim = new LoadSimulator(new DefaultSimulationGuard(limits, () -> false));
        java.util.function.Function<String, SimulationSpec> spec = u -> new SimulationSpec(URI.create(u), "GET", Map.of(), null, 10, Duration.ofSeconds(10), Duration.ZERO, 50, Duration.ofSeconds(1));
        assertThatThrownBy(() -> sim.start(spec.apply("http://api.prod.example.com/x"))).isInstanceOf(SimulationRefusedException.class).hasMessageContaining("allow-list");
        assertThatThrownBy(() -> sim.start(spec.apply("http://169.254.169.254/latest/meta-data"))).hasMessageContaining("link-local");
        assertThatThrownBy(() -> sim.start(spec.apply("file:///etc/passwd"))).hasMessageContaining("http");
        assertThatThrownBy(() -> sim.start(new SimulationSpec(url(""), "GET", Map.of(), null, 51, Duration.ofSeconds(1), Duration.ZERO, 50, null))).hasMessageContaining("virtualUsers");
        assertThatThrownBy(() -> sim.start(new SimulationSpec(url(""), "GET", Map.of(), null, 5, Duration.ofMinutes(2), Duration.ZERO, 50, null))).hasMessageContaining("duration");
        assertThatThrownBy(() -> sim.start(new SimulationSpec(url(""), "GET", Map.of(), null, 5, Duration.ofSeconds(1), Duration.ZERO, 0, null))).hasMessageContaining("capped");
        assertThatThrownBy(() -> sim.start(new SimulationSpec(url(""), "GET", Map.of(), null, 5, Duration.ofSeconds(1), Duration.ZERO, 500, null))).hasMessageContaining("capped");
        // allow-listed hosts and sub-domains pass the guard (nothing is sent: we only check)
        new DefaultSimulationGuard(limits, () -> false).check(spec.apply("https://api.test.example.com/x"));
        new DefaultSimulationGuard(limits, () -> false).check(spec.apply("https://eu.stage.example.com/x"));
        assertThat(hits).hasValue(0);
    }

    @Test
    void nothingRunsWhenProductionRulesApply() {
        LoadSimulator sim = new LoadSimulator(new DefaultSimulationGuard(DefaultSimulationGuard.Limits.defaults(), () -> true));
        assertThatThrownBy(() -> sim.start(SimulationSpec.post(url(""), null, 1, Duration.ofSeconds(1))))
                .isInstanceOf(SimulationRefusedException.class).hasMessageContaining("production");
        assertThat(hits).hasValue(0);
    }

    @Test
    void histogramPercentilesAreAccurateToAboutThreePercent() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 1; i <= 100_000; i++) {
            h.record(i * 10L); // 10 µs ... 1 s, uniform
        }
        assertThat(h.percentile(50)).isBetween(480_000L, 520_000L);
        assertThat(h.percentile(99)).isBetween(960_000L, 1_030_000L);
        assertThat(h.max()).isEqualTo(1_000_000L);
        assertThat(new LatencyHistogram().percentile(50)).isZero();
    }
}
