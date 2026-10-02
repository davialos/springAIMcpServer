package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.Fixtures;
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
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Generates a suite for the sample CRM (composed annotations, a generic CRUD base controller, entity request
 * bodies, a DTO with relationship ids, Spring Data REST, functional routes) and runs it with k6 against an
 * in-process server that behaves like the database behind it: foreign keys must exist, unique columns must not
 * repeat, column lengths are enforced, server-managed fields (id, version, audit, collections) are refused.
 * Every request must succeed — which only happens when seeding created parents before children and the load
 * test reused the created ids.
 */
class K6SeedingE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static Optional<String> k6;
    private static final Db DB = new Db();

    /** The in-memory "database": tables of rows by id, with the CRM's constraints. */
    static final class Db {
        final AtomicLong ids = new AtomicLong(1000);
        final Map<String, Map<Long, JsonNode>> tables = new ConcurrentHashMap<>();
        final List<String> rejected = Collections.synchronizedList(new ArrayList<>());
        final Set<String> uniques = ConcurrentHashMap.newKeySet();

        Map<Long, JsonNode> table(String name) {
            return tables.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
        }

        void clear() {
            tables.clear();
            rejected.clear();
            uniques.clear();
        }
    }

    @BeforeAll
    static void generateAndServe() throws IOException {
        suite = dir.resolve("crm-suite");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new LoadTestCli(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err),
                InputStream.nullInputStream()).execute(new String[]{"generate", "--project",
                Fixtures.sampleCrm().toString(), "--out", suite.toString(), "--no-db"});
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        ((ObjectNode) c.path("seed")).put("perTable", 4);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/crm/", K6SeedingE2ETest::handle);
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
        DB.clear();
    }

    // ── the fake CRM ─────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern ITEM = Pattern.compile("^/crm/(api|rest)/(companies|contacts|deals|tasks)/(\\d+)$");
    private static final Pattern COLLECTION =
            Pattern.compile("^/crm/(api|rest)/(companies|contacts|users|deals|tasks)$");

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = text.isBlank() ? null : Documents.parse(text);
        try {
            Matcher item = ITEM.matcher(path);
            Matcher coll = COLLECTION.matcher(path);
            if (item.matches()) {
                String table = item.group(2);
                long id = Long.parseLong(item.group(3));
                if (!DB.table(table).containsKey(id)) {
                    reply(ex, 404, "{}", method + " " + path + " unknown id");
                    return;
                }
                switch (method) {
                    case "GET" -> reply(ex, 200, DB.table(table).get(id).toString(), null);
                    case "PUT", "PATCH" -> {
                        String error = validate(table, body, id);
                        reply(ex, error == null ? 200 : 400, "{}", error);
                    }
                    case "DELETE" -> {
                        String blocker = referencedBy(table, id);
                        if (blocker != null) {
                            reply(ex, 409, "{}", "DELETE " + path + " still referenced by " + blocker);
                        } else {
                            DB.table(table).remove(id);
                            reply(ex, 204, null, null);
                        }
                    }
                    default -> reply(ex, 405, "{}", method + " " + path);
                }
                return;
            }
            if (coll.matches()) {
                String table = coll.group(2);
                if (method.equals("GET")) {
                    reply(ex, 200, "[]", null);
                    return;
                }
                String error = validate(table, body, null);
                if (error != null) {
                    reply(ex, error.startsWith("409") ? 409 : 400, "{}", method + " " + path + ": " + error);
                    return;
                }
                long id = DB.ids.incrementAndGet();
                DB.table(table).put(id, body);
                switch (table) {
                    // users and tasks answer like Spring Data REST: 201, Location header, no body
                    case "users", "tasks" -> {
                        ex.getResponseHeaders().add("Location",
                                "http://localhost/crm/" + coll.group(1) + "/" + table + "/" + id);
                        reply(ex, 201, null, null);
                    }
                    // deals wrap the created row
                    case "deals" -> reply(ex, 201, "{\"data\":{\"id\":" + id + "}}", null);
                    default -> reply(ex, 201, "{\"id\":" + id + "}", null);
                }
                return;
            }
            int status = method.equals("POST") ? 202 : 200; // info, version, health, reports
            reply(ex, status, "{\"ok\":true}", null);
        } catch (RuntimeException e) {
            reply(ex, 500, "{}", method + " " + path + " crashed: " + e);
        }
    }

    private static void reply(HttpExchange ex, int status, String body, String rejection) throws IOException {
        if (rejection != null) {
            DB.rejected.add(status + " " + rejection);
        }
        byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    /** The CRM's column and relationship constraints, as the database would enforce them. */
    private static String validate(String table, JsonNode b, Long self) {
        if (b == null || !b.isObject()) {
            return "no JSON body";
        }
        for (String managed : List.of("id", "version", "createdAt", "updatedAt", "contacts")) {
            if (b.has(managed) && !table.equals("users")) {
                return "server-managed field " + managed + " sent";
            }
        }
        return switch (table) {
            case "companies" -> firstError(
                    text(b, "name", 60, true), text(b, "registrationNo", 20, false),
                    unique("companies.registrationNo", b.path("registrationNo"), self));
            case "contacts" -> firstError(
                    text(b, "firstName", 40, true), text(b, "lastName", 40, false), text(b, "email", 80, false),
                    unique("contacts.email", b.path("email"), self),
                    exists("companies", b.path("company").path("id"), true));
            case "users" -> firstError(text(b, "username", 30, true),
                    unique("users.username", b.path("username"), self));
            case "deals" -> firstError(text(b, "title", 100, true),
                    exists("contacts", b.path("contactId"), true), exists("users", b.path("ownerId"), false),
                    b.path("amount").isNumber() && b.path("amount").asDouble() > 0 ? null : "amount must be > 0");
            case "tasks" -> text(b, "summary", 120, true);
            default -> "unknown table";
        };
    }

    private static String firstError(String... errors) {
        for (String e : errors) {
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static String text(JsonNode b, String field, int max, boolean required) {
        JsonNode v = b.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return required ? field + " is required" : null;
        }
        String s = v.asString();
        return s.isBlank() && required ? field + " is blank" : s.length() > max ? field + " longer than " + max : null;
    }

    private static String unique(String column, JsonNode v, Long self) {
        if (v.isMissingNode() || v.isNull() || self != null) {
            return null;
        }
        return DB.uniques.add(column + "=" + v.asString()) ? null : "409 duplicate " + column + " " + v.asString();
    }

    private static String exists(String table, JsonNode id, boolean required) {
        if (id.isMissingNode() || id.isNull()) {
            return required ? table + " reference missing" : null;
        }
        return DB.table(table).containsKey(id.asLong()) ? null : table + " " + id + " does not exist (foreign key)";
    }

    private static String referencedBy(String table, long id) {
        if (table.equals("companies")) {
            for (JsonNode c : DB.table("contacts").values()) {
                if (c.path("company").path("id").asLong() == id) {
                    return "a contact";
                }
            }
        }
        if (table.equals("contacts")) {
            for (JsonNode d : DB.table("deals").values()) {
                if (d.path("contactId").asLong() == id) {
                    return "a deal";
                }
            }
        }
        return null;
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    private record Outcome(int exit, String output) {
    }

    private static Outcome k6(String mode, Map<String, String> env) throws Exception {
        assumeThat(k6).as("k6 binary (K6_BIN or PATH)").isPresent();
        Map<String, String> all = new LinkedHashMap<>(env);
        all.put("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/crm");
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, mode, null, all, k6.get(), List.of()));
        Process p = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Outcome(p.waitFor(), output);
    }

    @Test
    void seedingFollowsTheRelationshipsAndEveryRequestOfTheLoadSucceeds() throws Exception {
        JsonNode seed = Documents.parse(Files.readString(suite.resolve("data/seed.json")));
        List<String> tables = new ArrayList<>();
        seed.forEach(s -> tables.add(s.path("table").asString()));
        assertThat(tables.indexOf("companies")).isLessThan(tables.indexOf("contacts"));
        assertThat(tables.indexOf("contacts")).isLessThan(tables.indexOf("deals"));
        assertThat(tables.indexOf("users")).isLessThan(tables.indexOf("deals"));

        Outcome o = k6("smoke", Map.of());
        assertThat(DB.rejected).as("requests the CRM refused").isEmpty();
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(o.output()).contains("seed: companies 4/4").contains("contacts 4/4").contains("users 4/4")
                .contains("deals 4/4").contains("task 4/4");
        // every contact points at an existing company, every deal at an existing contact and user
        for (JsonNode c : DB.table("contacts").values()) {
            assertThat(DB.table("companies")).containsKey(c.path("company").path("id").asLong());
        }
        for (JsonNode d : DB.table("deals").values()) {
            assertThat(DB.table("contacts")).containsKey(d.path("contactId").asLong());
        }
        assertThat(DB.table("deals").values()).anyMatch(d -> d.has("ownerId"));
        // entity bodies carry no server-managed field and respect the column lengths
        for (JsonNode c : DB.table("companies").values()) {
            assertThat(c.has("id") || c.has("version") || c.has("contacts")).isFalse();
            assertThat(c.path("registrationNo").asString("").length()).isLessThanOrEqualTo(20);
        }
    }

    @Test
    void seededRowsCanBeCleanedUpChildrenFirst() throws Exception {
        // read-only load (no rows created during the load), then delete what setup created
        Outcome o = k6("smoke", Map.of("API", "getCompany,getContact,getDeal", "SEED_CLEANUP", "true"));
        assertThat(DB.rejected).as("a refused delete means the order was wrong").isEmpty();
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(o.output()).contains("cleanup: task 4/4, deals 4/4"); // Task @ManyToOne Deal: tasks go first
        assertThat(DB.table("deals")).isEmpty();
        assertThat(DB.table("contacts")).isEmpty();
        assertThat(DB.table("companies")).isEmpty();
        assertThat(DB.table("tasks")).isEmpty();
        assertThat(DB.table("users")).hasSize(4); // no delete endpoint for users: they stay
    }

    @Test
    void seedingCanBeSwitchedOff() throws Exception {
        Outcome o = k6("smoke", Map.of("SEED", "false", "API", "getHealthPing,version,info"));
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(DB.tables.values().stream().mapToInt(Map::size).sum()).isZero();
        assertThat(new HashSet<>(DB.rejected)).isEmpty();
    }
}
