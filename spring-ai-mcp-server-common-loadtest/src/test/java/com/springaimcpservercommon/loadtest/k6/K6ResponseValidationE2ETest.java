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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Responses are checked against the schema read from the code, and lifecycle flows check that a read returns what
 * the write before it sent — against a service that can be switched into the three bugs these checks exist for: a
 * wrong type, a missing member and a lost update.
 */
class K6ResponseValidationE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static final AtomicLong IDS = new AtomicLong(500);
    /** id → {name, quantity, status, createdStatus}. */
    private static final Map<Long, Map<String, Object>> ROWS = new ConcurrentHashMap<>();
    private static final List<String> OUTPUT = Collections.synchronizedList(new ArrayList<>());
    private static volatile String bug = "none";
    private static final Pattern ITEM = Pattern.compile("^/shop/items/(\\d+)$");

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
                @RequestMapping("/items")
                class ItemController {
                    @PostMapping Item create(@RequestBody NewItem n) { return null; }
                    @GetMapping("/{id}") ResponseEntity<Item> get(@PathVariable Long id) { return null; }
                    @GetMapping List<Item> list() { return null; }
                    @PutMapping("/{id}") Item update(@PathVariable Long id, @RequestBody ItemUpdate u) { return null; }
                    @DeleteMapping("/{id}") void delete(@PathVariable Long id) { }
                }
                record NewItem(String name, int quantity) { }
                record ItemUpdate(String name, int quantity, ItemStatus status) { }
                record Item(Long id, String name, int quantity, ItemStatus status) { }
                enum ItemStatus { NEW, PAID, SHIPPED }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();
        Path config = suite.resolve("loadtest.config.json");
        var c = (tools.jackson.databind.node.ObjectNode) Documents.parse(Files.readString(config));
        ((tools.jackson.databind.node.ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        ((tools.jackson.databind.node.ObjectNode) c.path("journey")).put("pauseScale", 0);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/items", K6ResponseValidationE2ETest::handle);
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
        bug = "none";
        ROWS.clear();
        OUTPUT.clear();
    }

    private static String item(long id, Map<String, Object> row) {
        String quantity = bug.equals("type") ? "\"" + row.get("quantity") + "\"" : String.valueOf(row.get("quantity"));
        String status = bug.equals("lostUpdate") ? (String) row.get("createdStatus") : (String) row.get("status");
        StringBuilder json = new StringBuilder("{\"id\":").append(id).append(",\"name\":\"").append(row.get("name"))
                .append('"');
        if (!bug.equals("missing")) {
            json.append(",\"quantity\":").append(quantity);
        }
        return json.append(",\"status\":\"").append(status).append("\"}").toString();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = ITEM.matcher(path);
        if (method.equals("POST") && path.equals("/shop/items")) {
            JsonNode b = Documents.parse(body);
            long id = IDS.incrementAndGet();
            // the name is stored upper-case: a normalisation, not a lost write
            ROWS.put(id, new ConcurrentHashMap<>(Map.of("name", b.path("name").asString("x").toUpperCase(),
                    "quantity", b.path("quantity").asInt(0), "status", "NEW", "createdStatus", "NEW")));
            reply(ex, 201, item(id, ROWS.get(id)));
        } else if (method.equals("GET") && path.equals("/shop/items")) {
            reply(ex, 200, "[" + String.join(",", ROWS.entrySet().stream()
                    .map(e -> item(e.getKey(), e.getValue())).toList()) + "]");
        } else if (m.matches()) {
            long id = Long.parseLong(m.group(1));
            Map<String, Object> row = ROWS.get(id);
            if (row == null) {
                ex.sendResponseHeaders(404, -1);
            } else if (method.equals("GET")) {
                reply(ex, 200, item(id, row));
            } else if (method.equals("PUT")) {
                JsonNode b = Documents.parse(body);
                row.put("name", b.path("name").asString("x").toUpperCase());
                row.put("quantity", b.path("quantity").asInt(0));
                row.put("status", b.path("status").asString("NEW"));
                reply(ex, 200, item(id, row));
            } else if (method.equals("DELETE")) {
                ROWS.remove(id);
                ex.sendResponseHeaders(204, -1);
            } else {
                ex.sendResponseHeaders(405, -1);
            }
        } else {
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
        return LoadTestRunner.suite(suite).mode(mode).env("SEED", "false").env("VALIDATE_SAMPLE", "1")
                .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop").output(OUTPUT::add);
    }

    @Test
    void theSchemasComeFromTheReturnTypes() throws IOException {
        JsonNode schemas = Documents.parse(Files.readString(suite.resolve("data/response-schemas.json")));
        assertThat(schemas.propertyNames()).contains("getItem", "listItems", "createItem", "updateItem")
                .doesNotContain("deleteItem"); // void: nothing to validate
        JsonNode get = schemas.path("getItem");
        assertThat(get.path("properties").path("quantity").path("type").asString()).isEqualTo("integer");
        assertThat(get.path("required").toString()).isEqualTo("[\"quantity\"]");
        assertThat(schemas.path("listItems").path("type").asString()).isEqualTo("array");
        assertThat(Files.readString(suite.resolve("loadtest.config.json"))).contains("\"validation\"");
    }

    @Test
    void aServiceThatKeepsItsContractPasses() {
        LoadTestRunner.RunResult r = runner("lifecycle-smoke").run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(String.join("\n", OUTPUT)).doesNotContain("breaks its schema").doesNotContain("differs from the write");
    }

    @Test
    void aWrongTypeFailsTheRunAndNamesTheMember() {
        bug = "type";
        LoadTestRunner.RunResult r = runner("lifecycle-smoke").run();
        assertThat(r.passed()).as("response_schema_violations threshold").isFalse();
        assertThat(String.join("\n", OUTPUT)).contains("breaks its schema").contains("$.quantity: expected integer, got string");
    }

    @Test
    void aMissingRequiredMemberFailsTheRun() {
        bug = "missing";
        LoadTestRunner.RunResult r = runner("lifecycle-smoke").run();
        assertThat(r.passed()).isFalse();
        assertThat(String.join("\n", OUTPUT)).contains("$.quantity: required member is missing");
    }

    @Test
    void aLostUpdateIsCaughtByTheReadAfterTheWrite() {
        bug = "lostUpdate";
        LoadTestRunner.RunResult r = runner("lifecycle-smoke").run();
        assertThat(r.passed()).as("read_after_write_mismatches threshold").isFalse();
        assertThat(String.join("\n", OUTPUT)).contains("differs from the write before it")
                .containsPattern("status: wrote \\W+(PAID|SHIPPED)\\W+ read \\W+NEW");
    }

    @Test
    void logModeReportsWithoutFailingAndOffModeIsSilent() {
        bug = "type";
        LoadTestRunner.RunResult log = runner("lifecycle-smoke").env("VALIDATE_RESPONSES", "log").run();
        assertThat(log.passed()).as(log.output()).isTrue();
        assertThat(String.join("\n", OUTPUT)).contains("breaks its schema");
        OUTPUT.clear();
        LoadTestRunner.RunResult off = runner("lifecycle-smoke").env("VALIDATE_RESPONSES", "off").run();
        assertThat(off.passed()).isTrue();
        assertThat(String.join("\n", OUTPUT)).doesNotContain("breaks its schema");
    }
}
