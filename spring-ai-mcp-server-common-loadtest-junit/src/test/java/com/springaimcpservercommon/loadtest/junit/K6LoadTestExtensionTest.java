package com.springaimcpservercommon.loadtest.junit;

import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Used the way a host uses it next to {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}: the "application" is
 * an in-process server under the context path {@code /shop}, its port injected into a {@code @LocalServerPort}
 * field before each test.
 */
@K6LoadTest(project = "src/test/resources/sample-orders", outDir = "target/k6-extension-suite",
        env = {"SEED_PER_TABLE=2"})
class K6LoadTestExtensionTest {

    private static HttpServer server;
    private static final Map<Long, String> ORDERS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(100);

    @LocalServerPort
    int port;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/orders", ex -> {
            String path = ex.getRequestURI().getPath();
            int status;
            String body;
            if (ex.getRequestMethod().equals("POST")) {
                String json = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                long id = IDS.incrementAndGet();
                ORDERS.put(id, json);
                status = json.contains("\"product\"") ? 201 : 422;
                body = "{\"id\":" + id + "}";
            } else if (path.equals("/shop/orders")) {
                status = 200;
                body = "[]";
            } else {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                status = ORDERS.containsKey(id) ? 200 : 404; // only orders that exist
                body = "{}";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void injectPort() {
        port = server.getAddress().getPort(); // what Spring does for @LocalServerPort
    }

    @Test
    void theSuiteIsGeneratedOncePerClassAndTargetsTheTestsPort(K6Suite suite) {
        assertThat(suite.dir()).isEqualTo(Path.of("target/k6-extension-suite"));
        assertThat(suite.dir().resolve("main.js")).exists();
        assertThat(suite.generation().apis()).isEqualTo(3);
        assertThat(suite.generation().baseUrl()).isEqualTo("http://localhost:8181/shop");
        assertThat(suite.baseUrl()).isEqualTo("http://localhost:" + port + "/shop");
    }

    @Test
    void smokePassesAgainstTheApplication(K6Suite suite) throws IOException {
        LoadTestReport report = suite.assertPassed("smoke");
        assertThat(report.api("getOrder")).hasValueSatisfying(a -> assertThat(a.failedRate()).isZero());
        assertThat(report.api("createOrder")).hasValueSatisfying(a -> assertThat(a.requests()).isEqualTo(3));

        // the same report as baseline: no regression
        Path baseline = Files.copy(report.file(), suite.dir().resolve("baseline.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ReportComparison c = suite.withEnv("API", "getOrder,listOrders")
                .assertNoRegression("smoke", baseline, new ReportComparison.Rules(1000, 1000, 1, 1));
        assertThat(c.passed()).isTrue();
    }

    @Test
    void aFailingRunFailsTheTestWithTheReason(K6Suite suite) {
        // without seeding, getOrder asks for ids that do not exist: 404s fail the thresholds
        assertThatThrownBy(() -> suite.withEnv("SEED", "false").withEnv("API", "getOrder").assertPassed("smoke"))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("load test smoke failed (k6 exit " + LoadTestRunner.THRESHOLDS_FAILED + ")")
                .hasMessageContaining("http_req_failed{api:getOrder}")
                .hasMessageContaining("getOrder GET /orders/{id}: 100.0% failed");
    }

    @Nested
    class NestedClasses {

        @Test
        void inheritTheConfiguration(K6Suite suite) {
            assertThat(suite.dir()).isEqualTo(Path.of("target/k6-extension-suite"));
        }
    }
}
