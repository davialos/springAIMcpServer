package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.RealDataBinder;
import com.springaimcpservercommon.loadtest.data.TableIndex;
import com.springaimcpservercommon.loadtest.data.UserData;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Generates a suite for the sample project and runs it with the real k6 binary against an in-process HTTP server
 * that validates every payload the way the project's Bean Validation would. Skipped when k6 is not installed.
 */
class K6SuiteE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final List<String> INVALID = new ArrayList<>();
    private static Optional<String> k6;

    @BeforeAll
    static void generateAndServe() throws IOException {
        ApiCatalog catalog = CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop())));
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(new TableIndex(catalog.entities(), List.of()), Map.of()));
        suite = dir.resolve("suite");
        UserData user = new UserData(Map.of("CreateOrderRequest.deliveryNotes", List.of("Leave at door")), Map.of(),
                Map.of());
        new K6SuiteGenerator().generate(catalog, plan,
                Map.of("customers.id", List.of(1L, 2L, 3L), "product.sku", List.of("SKU-1", "SKU-2")), user,
                new K6SuiteGenerator.Options(suite, "http://localhost:1/shop", "auto", "none", null));
        // Tests run fast: no think time (also proves config edits are honoured).
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/", K6SuiteE2ETest::handle);
        server.start();
        k6 = Fixtures.k6();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceAll("/\\d+$", "/{n}");
        String key = ex.getRequestMethod() + " " + path.replaceAll("/SKU-\\d+$", "/{sku}");
        HITS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        byte[] in = ex.getRequestBody().readAllBytes();
        int status = switch (ex.getRequestMethod()) {
            case "POST" -> validate(path, in) ? 201 : 400;
            case "PUT" -> validate(path, in) ? 200 : 400;
            default -> 200;
        };
        byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    /** The sample project's Bean Validation rules, enforced on every generated body. */
    private static boolean validate(String path, byte[] body) {
        JsonNode b = Documents.parse(new String(body, StandardCharsets.UTF_8));
        List<String> errors = new ArrayList<>();
        if (path.endsWith("/orders")) {
            if (!b.path("customerId").isIntegralNumber()) {
                errors.add("customerId");
            }
            if (!b.path("lines").isArray() || b.path("lines").isEmpty()) {
                errors.add("lines");
            }
            for (JsonNode line : b.path("lines")) {
                if (line.path("productSku").asString("").isBlank()) {
                    errors.add("productSku");
                }
                if (line.has("quantity") && (line.path("quantity").asInt() < 1 || line.path("quantity").asInt() > 99)) {
                    errors.add("quantity " + line.path("quantity"));
                }
            }
            if (b.has("couponCode") && !b.path("couponCode").asString().matches("^[A-Z]{3}-\\d{4}$")) {
                errors.add("couponCode " + b.path("couponCode"));
            }
            if (b.has("status") && !List.of("NEW", "PAID", "SHIPPED", "CANCELLED").contains(b.path("status").asString())) {
                errors.add("status");
            }
        } else {
            String first = b.path("firstName").asString("");
            if (first.isBlank() || first.length() > 40 || b.path("lastName").asString("").isBlank()) {
                errors.add("name");
            }
            if (!b.path("email").asString("").matches("[^@\\s]+@[^@\\s]+\\.[a-z]+")) {
                errors.add("email " + b.path("email"));
            }
            int pw = b.path("password").asString("").length();
            if (pw < 8 || pw > 64) {
                errors.add("password");
            }
            if (b.has("birthDate") && !LocalDate.parse(b.path("birthDate").asString()).isBefore(LocalDate.now())) {
                errors.add("birthDate");
            }
            if (b.has("address") && b.path("address").has("zipCode") && b.path("address").path("zipCode").asString().length() != 5) {
                errors.add("zipCode");
            }
            if (b.has("tags") && b.path("tags").size() > 5) {
                errors.add("tags");
            }
        }
        if (!errors.isEmpty()) {
            synchronized (INVALID) {
                INVALID.add(path + " " + errors + " " + b);
            }
        }
        return errors.isEmpty();
    }

    private record Outcome(int exit, String output) {
    }

    private static Outcome k6(String mode, String dataMode, Map<String, String> env) throws Exception {
        assumeThat(k6).as("k6 binary (K6_BIN or PATH)").isPresent();
        Map<String, String> all = new java.util.LinkedHashMap<>(env);
        all.put("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop");
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, mode, dataMode, all, k6.get(), List.of()));
        Process p = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true).start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Outcome(p.waitFor(), output);
    }

    @Test
    void generatesTheSuiteLayout() throws IOException {
        for (String f : List.of("main.js", "hooks.js", "run.sh", "README.md", "loadtest.config.json", "lib/data.js",
                "lib/dummy.js", "lib/random.js", "lib/modes.js", "lib/http.js", "lib/report.js", "lib/dictionaries.json",
                "providers/schemas.js", "apis/createOrder.js", "apis/getCustomer.js", "data/real.json", "data/user.json",
                "data/plan.json")) {
            assertThat(suite.resolve(f)).as(f).exists();
        }
        String createOrder = Files.readString(suite.resolve("apis/createOrder.js"));
        assertThat(createOrder).contains("S.CreateOrderRequest(ctx)").contains("\"method\":\"POST\"");
        String schemas = Files.readString(suite.resolve("providers/schemas.js"));
        assertThat(schemas).contains("export function CreateOrderRequest(ctx)")
                .contains("\"real\":\"customers.id\"").contains("\"pattern\":\"^[A-Z]{3}-\\\\d{4}$\"")
                .doesNotContain("function OrderFilter"); // query object: flattened into query parameters
        assertThat(Files.readString(suite.resolve("README.md"))).contains("mixed-spike").contains("`getCustomer.path.id`");
    }

    @Test
    void regenerationKeepsHooksUserDataAndRemovesStaleApis() throws IOException {
        Path copy = dir.resolve("regen");
        ApiCatalog catalog = CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop())));
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(new TableIndex(catalog.entities(), List.of()), Map.of()));
        K6SuiteGenerator.Options o = new K6SuiteGenerator.Options(copy, "http://x", "auto", "none", null);
        new K6SuiteGenerator().generate(catalog, plan, Map.of("customers.id", List.of(7L)),
                new UserData(Map.of("email", List.of("a@example.com")), Map.of(), Map.of()), o);
        Files.writeString(copy.resolve("hooks.js"), "// mine\n");
        Files.writeString(copy.resolve("apis/oldApi.js"), JsEmitter.GENERATED + "\n");
        new K6SuiteGenerator().generate(CatalogMerger.filter(catalog, List.of("/api/v1/customers/**"), List.of()),
                plan, Map.of(), UserData.load(copy.resolve("data/user.json")), o);
        assertThat(Files.readString(copy.resolve("hooks.js"))).isEqualTo("// mine\n");
        assertThat(copy.resolve("apis/oldApi.js")).doesNotExist();
        assertThat(copy.resolve("apis/createOrder.js")).doesNotExist();
        assertThat(Files.readString(copy.resolve("data/user.json"))).contains("a@example.com");
        assertThat(Files.readString(copy.resolve("data/real.json"))).contains("customers.id").contains("7");
    }

    @Test
    void previewProducesValidPayloadsInEveryDataMode() throws Exception {
        for (String dataMode : List.of("auto", "dummy", "random", "real", "user", "mixed")) {
            Outcome o = k6("preview", dataMode, Map.of("PREVIEW_COUNT", "15"));
            assertThat(o.exit()).as(o.output()).isZero();
            List<JsonNode> lines;
            try (Stream<String> s = o.output().lines()) {
                lines = s.filter(l -> l.startsWith("{\"api\"")).map(Documents::parse).toList();
            }
            assertThat(lines).as(dataMode).hasSize(15 * 9); // DELETE disabled by default
            for (JsonNode l : lines) {
                assertThat(l.path("dataMode").asString()).isEqualTo(dataMode);
                if (l.path("api").asString().equals("createOrder")) {
                    assertThat(validate("/shop/api/v1/orders", l.path("body").toString().getBytes(StandardCharsets.UTF_8)))
                            .as(dataMode + " " + l).isTrue();
                }
                if (l.path("api").asString().equals("createCustomer")) {
                    assertThat(validate("/shop/api/v1/customers", l.path("body").toString().getBytes(StandardCharsets.UTF_8)))
                            .as(dataMode + " " + l).isTrue();
                }
                if (l.path("api").asString().equals("getCustomer")) {
                    assertThat(l.path("sources").path("getCustomer.path.id").asString()).isEqualTo("real");
                    assertThat(l.path("request").asString()).matches("GET /api/v1/customers/[123]");
                }
            }
            if (dataMode.equals("user")) {
                assertThat(lines).filteredOn(l -> l.path("api").asString().equals("createOrder")
                        && l.path("body").has("deliveryNotes"))
                        .allMatch(l -> l.path("body").path("deliveryNotes").asString().equals("Leave at door"));
            }
        }
    }

    @Test
    void smokeAndMixedModesHitTheServerWithValidRequestsAndWriteReports() throws Exception {
        Outcome smoke = k6("smoke", null, Map.of());
        assertThat(smoke.exit()).as(smoke.output()).isZero();
        assertThat(smoke.output()).contains("All thresholds passed.").contains("createOrder");
        assertThat(HITS.keySet()).contains("GET /shop/api/v1/customers", "GET /shop/api/v1/customers/{n}",
                "POST /shop/api/v1/orders", "PUT /shop/api/v1/customers/{n}", "GET /shop/api/v1/products/{sku}");
        assertThat(HITS.keySet()).noneMatch(k -> k.startsWith("DELETE"));

        Outcome mixed = k6("mixed-load", "mixed", Map.of("DURATION_SCALE", "0.02", "VUS", "3"));
        assertThat(mixed.exit()).as(mixed.output()).isZero();
        assertThat(INVALID).as("payloads rejected by validation").isEmpty();
        try (Stream<Path> reports = Files.list(suite.resolve("reports"))) {
            assertThat(reports.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.startsWith("smoke-") && n.endsWith(".md"))
                    .anyMatch(n -> n.startsWith("mixed-load-") && n.endsWith(".json"));
        }
    }

    @Test
    void perApiStressRunsOnlyTheSelectedApisAndRandomDataAccepts4xx() throws Exception {
        HITS.clear();
        Outcome o = k6("stress", "random", Map.of("DURATION_SCALE", "0.01", "VUS", "2", "API", "getOrder,searchOrder"));
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(HITS.keySet()).allMatch(k -> k.contains("/orders/"));
    }

    @Test
    void refusesProductionLookingTargetsAndUnknownApis() throws Exception {
        assumeThat(k6).isPresent();
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, "smoke", null,
                Map.of("BASE_URL", "https://api.prod.example.com"), k6.get(), List.of()));
        Process p = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).isNotZero();
        assertThat(out).contains("blockedHostPattern");

        Outcome unknown = k6("smoke", null, Map.of("API", "noSuchApi"));
        assertThat(unknown.exit()).isNotZero();
        assertThat(unknown.output()).contains("API=noSuchApi");
    }
}
