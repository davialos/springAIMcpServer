package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.data.LifecyclePlan;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Business flows generated from the code, and realistic row popularity, against an orders service whose creates answer
 * {@code 201 + Location} with no body: lifecycle flows (create → read → update → status transitions → action →
 * delete), hot-row skew and per-VU partitioning of writes.
 */
class K6LifecycleE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static final Map<Long, String> STATUS = new ConcurrentHashMap<>();
    private static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> REJECTED = Collections.synchronizedList(new ArrayList<>());
    private static final Pattern ITEM = Pattern.compile("^/shop/orders/(\\d+)(/ship)?$");

    @BeforeAll
    static void generateAndServe() throws IOException {
        Path project = dir.resolve("shop");
        Path src = Files.createDirectories(project.resolve("src/main/java/shop"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/shop\n");
        Files.writeString(src.resolve("OrderController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    @PostMapping Object create(@RequestBody NewOrder o) { return null; }
                    @GetMapping("/{id}") Object get(@PathVariable Long id) { return null; }
                    @PutMapping("/{id}") Object update(@PathVariable Long id, @RequestBody OrderUpdate u) { return null; }
                    @PostMapping("/{id}/ship") Object ship(@PathVariable Long id) { return null; }
                    @DeleteMapping("/{id}") Object delete(@PathVariable Long id) { return null; }
                }
                record NewOrder(String product, int quantity) { }
                record OrderUpdate(int quantity, OrderStatus status) { }
                enum OrderStatus { NEW, PAID, SHIPPED, DELIVERED }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();
        Path config = suite.resolve("loadtest.config.json");
        var c = (tools.jackson.databind.node.ObjectNode) Documents.parse(Files.readString(config));
        ((tools.jackson.databind.node.ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        ((tools.jackson.databind.node.ObjectNode) c.path("journey")).put("pauseScale", 0);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/orders", K6LifecycleE2ETest::handle);
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void clear() {
        STATUS.clear();
        LOG.clear();
        REJECTED.clear();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String call = method + " " + path;
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.add(call + (method.equals("PUT") ? " " + body : ""));
        Matcher m = ITEM.matcher(path);
        if (method.equals("POST") && path.equals("/shop/orders")) {
            long id = IDS.incrementAndGet();
            STATUS.put(id, "NEW");
            ex.getResponseHeaders().add("Location", "/shop/orders/" + id); // no body: the id is in the header only
            ex.sendResponseHeaders(201, -1);
        } else if (m.matches()) {
            long id = Long.parseLong(m.group(1));
            boolean exists = STATUS.containsKey(id);
            if (!exists) {
                REJECTED.add("404 " + call);
                ex.sendResponseHeaders(404, -1);
            } else if (method.equals("GET")) {
                reply(ex, 200, "{\"id\":" + id + ",\"status\":\"" + STATUS.get(id) + "\"}");
            } else if (method.equals("PUT")) {
                JsonNode b = Documents.parse(body);
                String status = b.path("status").asString("");
                if (!List.of("NEW", "PAID", "SHIPPED", "DELIVERED").contains(status)) {
                    REJECTED.add("422 " + call + " " + body);
                    ex.sendResponseHeaders(422, -1);
                } else {
                    STATUS.put(id, status);
                    reply(ex, 200, "{\"id\":" + id + "}");
                }
            } else if (method.equals("POST") && m.group(2) != null) {
                reply(ex, 200, "{\"id\":" + id + "}");
            } else if (method.equals("DELETE")) {
                STATUS.remove(id);
                ex.sendResponseHeaders(204, -1);
            } else {
                ex.sendResponseHeaders(405, -1);
            }
        } else {
            REJECTED.add("404 " + call);
            ex.sendResponseHeaders(404, -1);
        }
        ex.close();
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }

    private static LoadTestRunner runner(String mode) {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        return LoadTestRunner.suite(suite).mode(mode)
                .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop").output(l -> { });
    }

    @Test
    void theFlowsComeFromTheCode() {
        LifecyclePlan plan = LoadTestGenerator.builder().project(dir.resolve("shop")).noDatabase().build().discover()
                .lifecycle();
        assertThat(plan.flows()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("orders");
            List<String> apis = new ArrayList<>();
            f.steps().forEach(s -> apis.add(s.path("api").asString()));
            // create, read, update, 3 status transitions (each followed by a read), action + read, delete
            assertThat(apis).containsExactly("createOrder", "getOrder", "updateOrder", "updateOrder",
                    "getOrder", "updateOrder", "getOrder", "updateOrder", "getOrder", "ship", "getOrder",
                    "deleteOrder");
            assertThat(f.steps().get(0).path("stopOnFailure").asBoolean()).isTrue();
            assertThat(f.steps().get(11).path("ownRow").asBoolean()).isTrue();
            assertThat(f.steps().get(3).path("set").path("status").asString()).isEqualTo("PAID");
        });
    }

    @Test
    void aLifecycleWalksTheRowThroughItsLifeAndCleansUpAfterItself() {
        LoadTestRunner.RunResult r = runner("lifecycle-smoke").env("SEED", "false").run();
        assertThat(REJECTED).as("requests the shop refused").isEmpty();
        assertThat(r.passed()).as(r.output()).isTrue();
        // three iterations, each: create → read → update → PAID/SHIPPED/DELIVERED with reads → ship → read → delete
        assertThat(LOG.stream().filter(l -> l.startsWith("POST /shop/orders ") || l.equals("POST /shop/orders"))
                .count()).isEqualTo(3);
        List<String> puts = LOG.stream().filter(l -> l.startsWith("PUT")).toList();
        assertThat(puts).hasSize(12);
        List<String> statuses = puts.stream().map(l -> Documents.parse(l.substring(l.indexOf('{'))).path("status")
                .asString()).toList();
        assertThat(statuses.subList(1, 4)).containsExactly("PAID", "SHIPPED", "DELIVERED");
        assertThat(LOG.stream().filter(l -> l.startsWith("DELETE")).count()).isEqualTo(3); // DELETE is off for shared data
        assertThat(STATUS).as("every row the flows created was deleted again").isEmpty();
        // the report lists the enabled APIs: deleteOrder is off for shared data, its own-row deletes ran regardless
        assertThat(r.report().orElseThrow().apis()).extracting(a -> a.api())
                .contains("createOrder", "getOrder", "updateOrder", "ship").doesNotContain("deleteOrder");
    }

    private Map<String, Long> hits(String idPattern) {
        Pattern id = Pattern.compile("/orders/(\\d+)");
        return LOG.stream().filter(l -> l.matches(idPattern)).map(l -> {
            Matcher m = id.matcher(l);
            m.find();
            return m.group(1);
        }).collect(Collectors.groupingBy(x -> x, HashMap::new, Collectors.counting()));
    }

    @Test
    void hotRowsTakeMostRequestsWhenSkewIsOn() {
        runner("smoke").env("SEED_PER_TABLE", "20").env("API", "getOrder").env("ITERATIONS", "300").run();
        Map<String, Long> uniform = hits("GET /shop/orders/\\d+");
        long uniformTop = uniform.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        clear();
        runner("smoke").env("SEED_PER_TABLE", "20").env("API", "getOrder").env("ITERATIONS", "300").env("SKEW", "hot").run();
        Map<String, Long> hot = hits("GET /shop/orders/\\d+");
        long total = hot.values().stream().mapToLong(Long::longValue).sum();
        long top = hot.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(total).isEqualTo(300);
        assertThat(uniformTop).as("uniform: no row dominates").isLessThan(60);
        assertThat(top).as("hot: one row of 20 takes ~80% of the traffic").isGreaterThan(180);
        clear();
        runner("smoke").env("SEED_PER_TABLE", "20").env("API", "getOrder").env("ITERATIONS", "300").env("SKEW", "zipf").run();
        Map<String, Long> zipf = hits("GET /shop/orders/\\d+");
        List<Long> sorted = zipf.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(sorted.getFirst()).as("zipf: rank 1 beats rank 5 clearly").isGreaterThan(sorted.get(4) * 2);
    }

    @Test
    void eachVuWritesToItsOwnRowsWhenPartitioned() {
        runner("smoke").env("SEED_PER_TABLE", "40").env("API", "updateOrder").env("VUS", "4").env("ITERATIONS", "25")
                .env("PARTITION", "vu").run();
        Map<String, Long> partitioned = hits("PUT /shop/orders/\\d+ .*");
        assertThat(partitioned.keySet()).as("4 VUs, one slice of the pool each: 4 rows, never shared").hasSize(4);
        assertThat(partitioned.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(100);
        clear();
        runner("smoke").env("SEED_PER_TABLE", "40").env("API", "updateOrder").env("VUS", "4").env("ITERATIONS", "25").run();
        assertThat(hits("PUT /shop/orders/\\d+ .*").keySet()).as("shared pool: writes spread over many rows")
                .hasSizeGreaterThan(15);
    }
}
