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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Spring Security-aware authentication against a service that enforces it: per-role identities and public
 * endpoints read from the code (HTTP Basic), OAuth2 client credentials with a token revoked mid-run, and form login
 * with CSRF and one identity per VU.
 */
class K6AuthE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static HttpServer server;
    private static volatile String mode = "basic";
    private static String pristineConfig;
    /** route → who called it (user names), and whether an Authorization header came along. */
    private static final Map<String, Set<String>> CALLERS = new ConcurrentHashMap<>();
    private static final Set<String> PUBLIC_WITH_CREDENTIALS = ConcurrentHashMap.newKeySet();
    private static final List<String> DENIED = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final AtomicInteger AUTHENTICATED_CALLS = new AtomicInteger();
    private static final AtomicInteger ORDER_IDS = new AtomicInteger(100);
    private static final AtomicInteger REVOKED = new AtomicInteger();

    @BeforeAll
    static void generateAndServe() throws IOException {
        Path project = dir.resolve("shop");
        Path src = Files.createDirectories(project.resolve("src/main/java/shop"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/shop\n");
        Files.writeString(src.resolve("SecurityConfig.java"), """
                package shop;
                @Configuration
                @EnableWebSecurity
                class SecurityConfig {
                    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
                        http.csrf(c -> c.disable())
                            .authorizeHttpRequests(a -> a.requestMatchers("/public/**").permitAll().anyRequest().authenticated())
                            .httpBasic(Customizer.withDefaults());
                        return http.build();
                    }
                }
                """);
        Files.writeString(src.resolve("OrderController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class OrderController {
                    @GetMapping("/orders") Object list() { return null; }
                    @PostMapping("/orders") Object create(@RequestBody NewOrder o) { return null; }
                    @PreAuthorize("hasRole('ADMIN')") @GetMapping("/admin/stats") Object stats() { return null; }
                    @GetMapping("/public/ping") Object ping() { return null; }
                }
                record NewOrder(String product, int quantity) { }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();
        pristineConfig = Files.readString(suite.resolve("loadtest.config.json"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", K6AuthE2ETest::handle);
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() throws IOException {
        Files.writeString(suite.resolve("loadtest.config.json"), pristineConfig); // each test edits its own copy
        mode = "basic";
        CALLERS.clear();
        PUBLIC_WITH_CREDENTIALS.clear();
        DENIED.clear();
        TOKENS.clear();
        TOKEN_REQUESTS.set(0);
        AUTHENTICATED_CALLS.set(0);
        REVOKED.set(0);
    }

    // ── the service ────────────────────────────────────────────────────────────────────────────────────

    private static String header(HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "" : v;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static String basicUser(String authorization) {
        if (!authorization.startsWith("Basic ")) {
            return null;
        }
        String[] up = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8).split(":", 2);
        return up.length == 2 && !up[0].isEmpty() ? up[0] : null;
    }

    /** The authenticated user for the current mode, or {@code null}. */
    private static String principal(HttpExchange ex) {
        return switch (mode) {
            case "basic" -> basicUser(header(ex, "Authorization"));
            case "oauth2" -> {
                String a = header(ex, "Authorization");
                yield a.startsWith("Bearer ") ? TOKENS.get(a.substring(7)) : null;
            }
            default -> {
                String cookie = header(ex, "Cookie");
                int i = cookie.indexOf("SESSION=");
                yield i < 0 ? null : cookie.substring(i + 8).split(";")[0];
            }
        };
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath().replaceFirst("^/shop", "");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/token") && method.equals("POST")) {
            String client = basicUser(header(ex, "Authorization"));
            if (client == null) {
                send(ex, 401, "{}", null);
                return;
            }
            TOKEN_REQUESTS.incrementAndGet();
            String token = client + "-" + TOKENS.size() + "-" + TOKEN_REQUESTS.get();
            TOKENS.put(token, client);
            send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}", null);
            return;
        }
        if (path.equals("/login") && method.equals("GET")) {
            send(ex, 200, "<html><form><input type=\"hidden\" name=\"_csrf\" value=\"page-csrf\"/></form></html>", "text/html");
            return;
        }
        if (path.equals("/login") && method.equals("POST")) {
            Map<String, String> f = form(body);
            if (!"page-csrf".equals(f.get("_csrf")) || !"pw".equals(f.get("password"))) {
                send(ex, 401, "{}", null);
                return;
            }
            String user = f.get("username");
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + user + "; Path=/");
            ex.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=xsrf-" + user + "; Path=/");
            send(ex, 200, "{}", null);
            return;
        }
        String route = method + " " + path;
        if (path.equals("/public/ping")) {
            if (!header(ex, "Authorization").isEmpty() || !header(ex, "Cookie").isEmpty()) {
                PUBLIC_WITH_CREDENTIALS.add(route);
            }
            send(ex, 200, "{\"ok\":true}", null);
            return;
        }
        String user = principal(ex);
        if (user == null) {
            DENIED.add("401 " + route);
            send(ex, 401, "{}", null);
            return;
        }
        // simulate expiry: every token is revoked once, after a few calls
        if (mode.equals("oauth2") && AUTHENTICATED_CALLS.incrementAndGet() == 6 && REVOKED.getAndIncrement() == 0) {
            TOKENS.clear();
            DENIED.add("401 " + route + " (revoked)");
            send(ex, 401, "{}", null);
            return;
        }
        boolean admin = user.startsWith("root") || user.startsWith("admin");
        if (path.equals("/admin/stats") && !admin) {
            DENIED.add("403 " + route + " as " + user);
            send(ex, 403, "{}", null);
            return;
        }
        if (mode.equals("form") && method.equals("POST") && !("xsrf-" + user).equals(header(ex, "X-XSRF-TOKEN"))) {
            DENIED.add("403 " + route + " csrf as " + user);
            send(ex, 403, "{}", null);
            return;
        }
        CALLERS.computeIfAbsent(route, k -> ConcurrentHashMap.newKeySet()).add(user);
        if (method.equals("POST")) {
            send(ex, 201, "{\"id\":" + ORDER_IDS.incrementAndGet() + "}", null);
        } else {
            send(ex, 200, path.equals("/orders") ? "[]" : "{\"ok\":true}", null);
        }
    }

    private static void send(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type == null ? "application/json" : type);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    // ── the suite ──────────────────────────────────────────────────────────────────────────────────────

    private static ObjectNode config() throws IOException {
        return (ObjectNode) Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
    }

    private static void save(ObjectNode c) throws IOException {
        ((ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        Files.writeString(suite.resolve("loadtest.config.json"), c.toString());
    }

    private static LoadTestRunner runner() {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        return LoadTestRunner.suite(suite).mode("smoke").env("SEED", "false").env("VALIDATE_RESPONSES", "off")
                .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/shop");
    }

    @Test
    void theSetupAndRolesComeFromTheCode() throws IOException {
        JsonNode c = config();
        assertThat(c.path("auth").path("type").asString()).as("httpBasic in the filter chain").isEqualTo("basic");
        assertThat(c.path("auth").path("form").path("csrf").asBoolean()).as("csrf(disable)").isFalse();
        assertThat(c.path("auth").path("users").propertyNames()).containsExactly("ADMIN");
        assertThat(c.path("auth").path("users").path("ADMIN").path("username").asString()).isEqualTo("${ADMIN_USER}");
        assertThat(c.path("apis").path("stats").path("auth").asString()).isEqualTo("ADMIN");
        assertThat(c.path("apis").path("ping").path("auth").asString()).as("permitAll").isEqualTo("none");
        assertThat(c.path("apis").path("listOrders").has("auth")).isFalse();
    }

    @Test
    void basicAuthUsesTheRolesIdentityPerApiAndNoCredentialsForPublicEndpoints() throws IOException {
        save(config());
        LoadTestRunner.RunResult r = runner().env("AUTH_USER", "alice").env("AUTH_PASSWORD", "pw")
                .env("ADMIN_USER", "root").env("ADMIN_PASSWORD", "rootpw").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(DENIED).isEmpty();
        assertThat(CALLERS.get("GET /admin/stats")).containsExactly("root");
        assertThat(CALLERS.get("GET /orders")).containsExactly("alice");
        assertThat(PUBLIC_WITH_CREDENTIALS).as("a permitAll endpoint is called without credentials").isEmpty();
    }

    @Test
    void oauth2ClientCredentialsPerRoleAndANewTokenAfterARevocation() throws IOException {
        ObjectNode c = config();
        ((ObjectNode) c.path("auth")).put("type", "oauth2");
        save(c);
        mode = "oauth2";
        LoadTestRunner.RunResult r = runner().env("OAUTH_TOKEN_URL", "/token")
                .env("OAUTH_CLIENT_ID", "app").env("OAUTH_CLIENT_SECRET", "s")
                .env("ADMIN_CLIENT_ID", "admin-client").env("ADMIN_CLIENT_SECRET", "s").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(CALLERS.get("GET /admin/stats")).allMatch(u -> u.startsWith("admin-client"));
        assertThat(CALLERS.get("GET /orders")).allMatch(u -> u.startsWith("app"));
        assertThat(REVOKED.get()).as("the revocation happened").isEqualTo(1);
        // the revocation drops every token: each identity gets one 401 and logs in again, the run itself passes
        assertThat(DENIED).as("only 401s, each answered by a new login").isNotEmpty().hasSizeLessThanOrEqualTo(2)
                .allMatch(d -> d.startsWith("401 "));
        assertThat(TOKEN_REQUESTS.get()).as("two identities + a new token per refused one")
                .isEqualTo(2 + DENIED.size());
    }

    @Test
    void formLoginKeepsASessionPerVuSendsTheCsrfTokenAndEachVuHasItsOwnUser() throws IOException {
        ObjectNode c = config();
        ObjectNode auth = (ObjectNode) c.path("auth");
        auth.put("type", "form");
        ((ObjectNode) auth.path("form")).put("csrf", true);
        ObjectNode users = (ObjectNode) auth.path("users");
        users.putObject("U").put("username", "user-${VU}").put("password", "pw");
        auth.put("defaultRole", "U");
        ((ObjectNode) users.path("ADMIN")).put("username", "root-${VU}").put("password", "pw");
        save(c);
        mode = "form";
        LoadTestRunner.RunResult r = runner().env("VUS", "3").env("ITERATIONS", "2").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(DENIED).as("sessions, CSRF tokens and roles were all accepted").isEmpty();
        assertThat(CALLERS.get("POST /orders")).as("one identity per VU").hasSize(3).allMatch(u -> u.startsWith("user-"));
        assertThat(CALLERS.get("GET /admin/stats")).hasSize(3).allMatch(u -> u.startsWith("root-"));
    }
}
