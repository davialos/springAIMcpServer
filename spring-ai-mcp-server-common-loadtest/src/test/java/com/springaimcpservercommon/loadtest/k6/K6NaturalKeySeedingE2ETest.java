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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * A MyBatis-style Spring app (no JPA; tables only in a Flyway script, without foreign-key constraints) whose
 * rows are addressed by natural keys the server generates: {@code /articles/{slug}}, {@code /profiles/{username}}.
 * Seeding must create users before articles before comments, capture the slug from the create response, and the
 * load must only ever ask for rows that exist; cleanup deletes articles by slug.
 */
class K6NaturalKeySeedingE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static Optional<String> k6;

    private static final AtomicLong IDS = new AtomicLong(1);
    private static final Map<String, JsonNode> USERS = new ConcurrentHashMap<>();
    private static final Map<String, JsonNode> ARTICLES = new ConcurrentHashMap<>();
    private static final Map<Long, String> COMMENTS = new ConcurrentHashMap<>(); // id → article slug
    private static final List<String> REJECTED = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void generateAndServe() throws IOException {
        Path project = dir.resolve("blog");
        Path migration = Files.createDirectories(project.resolve("src/main/resources/db/migration"));
        Files.writeString(project.resolve("src/main/resources/application.properties"), "server.port=8099\n");
        Files.writeString(migration.resolve("V1__create_tables.sql"), """
                create table users (id varchar(255) primary key, username varchar(40) UNIQUE, email varchar(255));
                create table articles (id varchar(255) primary key, user_id varchar(255), slug varchar(255) UNIQUE,
                                       title varchar(120), body text);
                create table comments (id bigint primary key, body text, article_id varchar(255),
                                       user_id varchar(255));
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/blog"));
        Files.writeString(src.resolve("BlogApi.java"), """
                package blog;
                import jakarta.validation.constraints.NotBlank;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class BlogApi {
                    @PostMapping("/users") Object register(@RequestBody NewUser u) { return null; }
                    @GetMapping("/profiles/{username}") Object profile(@PathVariable String username) { return null; }
                    @PostMapping("/articles") Object create(@RequestBody NewArticle a) { return null; }
                    @GetMapping("/articles/{slug}") Object article(@PathVariable String slug) { return null; }
                    @DeleteMapping("/articles/{slug}") Object deleteArticle(@PathVariable String slug) { return null; }
                    @PostMapping("/articles/{slug}/comments")
                    Object comment(@PathVariable String slug, @RequestBody NewComment c) { return null; }
                    @DeleteMapping("/comments/{id}") Object deleteComment(@PathVariable Long id) { return null; }
                }
                record NewUser(@NotBlank String username, String email, String password) { }
                record NewArticle(@NotBlank String title, String body) { }
                record NewComment(String body) { }
                """);
        suite = dir.resolve("blog-suite");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new LoadTestCli(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err),
                InputStream.nullInputStream()).execute(new String[]{"generate", "--project", project.toString(),
                "--out", suite.toString(), "--no-db"});
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("schema: 3 tables, 3 foreign keys");
        Path config = suite.resolve("loadtest.config.json");
        ObjectNode c = (ObjectNode) Documents.parse(Files.readString(config));
        ((ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        ((ObjectNode) c.path("seed")).put("perTable", 4);
        Files.writeString(config, c.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", K6NaturalKeySeedingE2ETest::handle);
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
        USERS.clear();
        ARTICLES.clear();
        COMMENTS.clear();
        REJECTED.clear();
    }

    // ── the fake blog ────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern ARTICLE = Pattern.compile("^/articles/([^/]+)$");
    private static final Pattern ARTICLE_COMMENTS = Pattern.compile("^/articles/([^/]+)/comments$");
    private static final Pattern PROFILE = Pattern.compile("^/profiles/([^/]+)$");
    private static final Pattern COMMENT = Pattern.compile("^/comments/(\\d+)$");

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        String call = method + " " + path;
        try {
            JsonNode body = null;
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length > 0) {
                body = Documents.parse(new String(raw, StandardCharsets.UTF_8));
            }
            Matcher m;
            if (call.equals("POST /users")) {
                String username = body == null ? "" : body.path("username").asString("");
                if (username.isEmpty() || username.length() > 40 || USERS.containsKey(username)) {
                    reply(ex, 422, "{}", call + " bad username " + username);
                    return;
                }
                USERS.put(username, body);
                // like realworld: no id in the response, the natural key is what clients use
                reply(ex, 201, "{\"user\":{\"username\":" + Documents.json().writeValueAsString(username)
                        + ",\"token\":\"t\"}}", null);
            } else if (call.equals("POST /articles")) {
                String title = body == null ? "" : body.path("title").asString("");
                if (title.isEmpty() || title.length() > 120) {
                    reply(ex, 422, "{}", call + " bad title");
                    return;
                }
                String slug = "post-" + IDS.getAndIncrement(); // generated by the server, not sent by the client
                ARTICLES.put(slug, body);
                reply(ex, 201, "{\"article\":{\"slug\":\"" + slug + "\",\"title\":\"x\"}}", null);
            } else if (method.equals("GET") && (m = ARTICLE.matcher(path)).matches()) {
                exists(ex, call, ARTICLES.containsKey(decode(m.group(1))));
            } else if (method.equals("GET") && (m = PROFILE.matcher(path)).matches()) {
                exists(ex, call, USERS.containsKey(decode(m.group(1))));
            } else if (method.equals("POST") && (m = ARTICLE_COMMENTS.matcher(path)).matches()) {
                String slug = decode(m.group(1));
                if (!ARTICLES.containsKey(slug)) {
                    reply(ex, 404, "{}", call + " no such article");
                    return;
                }
                long id = IDS.getAndIncrement();
                COMMENTS.put(id, slug);
                reply(ex, 201, "{\"comment\":{\"id\":" + id + "}}", null);
            } else if (method.equals("DELETE") && (m = COMMENT.matcher(path)).matches()) {
                reply(ex, COMMENTS.remove(Long.parseLong(m.group(1))) != null ? 204 : 404, null, null);
            } else if (method.equals("DELETE") && (m = ARTICLE.matcher(path)).matches()) {
                String slug = decode(m.group(1));
                if (COMMENTS.containsValue(slug)) {
                    reply(ex, 409, "{}", call + " still has comments");
                    return;
                }
                if (ARTICLES.remove(slug) == null) {
                    reply(ex, 404, "{}", call + " no such article");
                    return;
                }
                reply(ex, 204, null, null);
            } else {
                reply(ex, 404, "{}", call + " unknown");
            }
        } catch (RuntimeException e) {
            reply(ex, 500, "{}", call + " crashed: " + e);
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void exists(HttpExchange ex, String call, boolean found) throws IOException {
        reply(ex, found ? 200 : 404, "{}", found ? null : call + " not found");
    }

    private static void reply(HttpExchange ex, int status, String body, String rejection) throws IOException {
        if (rejection != null) {
            REJECTED.add(status + " " + rejection);
        }
        byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    private record Outcome(int exit, String output) {
    }

    private static Outcome k6(String mode, Map<String, String> env) throws Exception {
        assumeThat(k6).as("k6 binary (K6_BIN or PATH)").isPresent();
        Map<String, String> all = new LinkedHashMap<>(env);
        all.put("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, mode, null, all, k6.get(), List.of()));
        Process p = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Outcome(p.waitFor(), output);
    }

    @Test
    void naturalKeysCreatedInSetupAreTheOnlyOnesTheLoadAsksFor() throws Exception {
        JsonNode seed = Documents.parse(Files.readString(suite.resolve("data/seed.json")));
        List<String> tables = new ArrayList<>();
        seed.forEach(s -> tables.add(s.path("table").asString()));
        assertThat(tables).containsExactly("users", "articles", "comments");

        Outcome o = k6("smoke", Map.of());
        assertThat(REJECTED).as("requests the blog refused").isEmpty();
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(o.output()).contains("seed: users 4/4, articles 4/4, comments 4/4");
        assertThat(COMMENTS.values()).allMatch(ARTICLES::containsKey);
    }

    @Test
    void seededArticlesAreDeletedBySlugAfterTheirComments() throws Exception {
        Outcome o = k6("smoke", Map.of("API", "article,profile", "SEED_CLEANUP", "true"));
        assertThat(REJECTED).as("a refused delete means the order or the key was wrong").isEmpty();
        assertThat(o.exit()).as(o.output()).isZero();
        assertThat(o.output()).contains("cleanup: comments 4/4, articles 4/4");
        assertThat(ARTICLES).isEmpty();
        assertThat(COMMENTS).isEmpty();
        assertThat(USERS).hasSize(4); // no delete endpoint for users
    }
}
