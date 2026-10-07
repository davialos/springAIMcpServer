package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.RealDataBinder;
import com.springaimcpservercommon.loadtest.data.RecordedTraffic;
import com.springaimcpservercommon.loadtest.data.TableIndex;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.discovery.HarCapture;
import com.springaimcpservercommon.loadtest.discovery.HarReader;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Replays the sample browser recording with k6 against a server that returns different ids than the recording
 * did: the journey must follow the live ids, never send recorded secrets, and skip disabled DELETE steps.
 */
class K6JourneyE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
    private static Optional<String> k6;

    @BeforeAll
    static void generateAndServe() throws IOException {
        ApiCatalog source = new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop());
        List<String> known = new ArrayList<>();
        source.endpoints().forEach(e -> known.add(e.path()));
        HarCapture har = new HarReader(s -> { }).read(Fixtures.sampleShopHar(),
                new HarReader.Options(List.of(), "/shop", known));
        ApiCatalog catalog = CatalogMerger.filter(CatalogMerger.merge(List.of(source, har.catalog())), List.of(),
                CatalogMerger.DEFAULT_EXCLUDES);
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(new TableIndex(catalog.entities(), List.of()), Map.of()));
        RecordedTraffic recorded = new RecordedTraffic(List.of(har), catalog, plan);
        suite = dir.resolve("suite");
        new K6SuiteGenerator().generate(catalog, plan, Map.of(), recorded.userData(), recorded.journey(),
                new K6SuiteGenerator.Options(suite, "http://localhost:1/shop", "auto", "none", null));
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("journey")).put("pauseScale", 0.01);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/", K6JourneyE2ETest::handle);
        server.start();
        k6 = Fixtures.k6();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void clear() {
        REQUESTS.clear();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        REQUESTS.add(ex.getRequestMethod() + " " + path + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                + " cookie=" + ex.getRequestHeaders().getFirst("Cookie") + " tenant="
                + ex.getRequestHeaders().getFirst("X-Tenant-Id") + " " + body);
        String response = switch (ex.getRequestMethod() + " " + path.replaceAll("/\\d+$", "/{n}")) {
            case "GET /shop/api/v1/customers" -> "{\"content\":[{\"id\":7}]}";
            case "GET /shop/api/v1/customers/{n}" -> "{\"id\":7}";
            case "POST /shop/api/v1/orders" -> "{\"id\":5555}";
            case "GET /shop/api/v1/products" -> "[{\"sku\":\"LIVE-1\"},{\"sku\":\"LIVE-2\"}]";
            default -> "{}";
        };
        int status = ex.getRequestMethod().equals("POST") && path.endsWith("/orders") ? 201 : 200;
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static int k6(String mode, String dataMode) throws Exception {
        assumeThat(k6).as("k6 binary (K6_BIN or PATH)").isPresent();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop");
        env.put("VALIDATE_RESPONSES", "off"); // the stub answers with canned bodies; see K6ResponseValidationE2ETest
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, mode, dataMode, env, k6.get(), List.of()));
        Process p = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        assertThat(exit).as(output).isZero();
        return exit;
    }

    @Test
    void replaysTheRecordedFlowFollowingLiveIds() throws Exception {
        k6("journey-smoke", null);
        List<String> one = REQUESTS.subList(0, 9);
        assertThat(one).extracting(r -> r.substring(0, r.indexOf(" auth="))).containsExactly(
                "POST /shop/api/v1/auth/login", "GET /shop/api/v1/customers", "GET /shop/api/v1/customers/7",
                "POST /shop/api/v1/orders", "GET /shop/api/v1/orders/5555", "GET /shop/api/v1/products",
                "GET /shop/api/v1/products/LIVE-1", "GET /shop/api/v1/products/LIVE-2",
                "PUT /shop/api/v1/customers/7");
        assertThat(REQUESTS).hasSize(27); // 3 iterations; the DELETE step is skipped (disabled by default)
        assertThat(REQUESTS).allMatch(r -> r.contains("auth=null") && r.contains("cookie=null") && r.contains("tenant=acme"));
        assertThat(String.join("\n", REQUESTS)).doesNotContain("S3cret!pw").doesNotContain("N3wPassw0rd!");
        String order = one.get(3);
        assertThat(order).contains("\"customerId\":7").contains("\"couponCode\":\"ABC-1234\"");
        assertThat(one.getFirst()).contains("\"username\":\"alice\"").contains("\"password\":\"");
    }

    @Test
    void generatedDataModesKeepOrderAndCorrelationsOnly() throws Exception {
        k6("journey-smoke", "dummy");
        List<String> one = REQUESTS.subList(0, 9);
        assertThat(one.get(4)).startsWith("GET /shop/api/v1/orders/5555");
        assertThat(one.get(3)).doesNotContain("ABC-1234").doesNotContain("Ring twice");
    }
}
