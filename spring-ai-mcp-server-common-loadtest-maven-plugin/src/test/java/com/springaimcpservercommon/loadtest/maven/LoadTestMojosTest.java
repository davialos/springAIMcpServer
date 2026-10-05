package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.sun.net.httpserver.HttpServer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The goals, configured the way Maven injects them (fields), over a small Spring project. */
class LoadTestMojosTest {

    @TempDir
    Path dir;

    private Path project() throws IOException {
        Path p = dir.resolve("shop");
        Path src = Files.createDirectories(p.resolve("src/main/java/shop"));
        Files.createDirectories(p.resolve("src/main/resources"));
        Files.writeString(p.resolve("src/main/resources/application.properties"), "server.port=8181\n");
        Files.writeString(src.resolve("OrderController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    @GetMapping("/{id}") Object get(@PathVariable Long id) { return null; }
                    @GetMapping Object list() { return null; }
                    @PostMapping Object create(@RequestBody NewOrder o) { return null; }
                }
                record NewOrder(String customerEmail, int quantity) { }
                """);
        return p;
    }

    private GenerateMojo generate(Path project, Path out) {
        GenerateMojo m = new GenerateMojo();
        m.project = project.toFile();
        m.outDir = out.toFile();
        m.database = false;
        m.values = Map.of("NewOrder.quantity", "1, 2");
        return m;
    }

    @Test
    void discoverAndGenerate() throws Exception {
        Path project = project();
        DiscoverMojo discover = new DiscoverMojo();
        discover.project = project.toFile();
        discover.execute();

        Path out = dir.resolve("suite");
        generate(project, out).execute();
        assertThat(out.resolve("main.js")).exists();
        assertThat(out.resolve("grafana/docker-compose.yml")).exists();
        assertThat(Files.readString(out.resolve("loadtest.config.json"))).contains("http://localhost:8181");
        assertThat(Files.readString(out.resolve("data/user.json"))).contains("NewOrder.quantity");

        GenerateMojo skipped = generate(project, dir.resolve("skipped"));
        skipped.skip = true;
        skipped.execute();
        assertThat(dir.resolve("skipped")).doesNotExist();
    }

    @Test
    void badInputFailsTheGoalNotTheBuildTool() {
        GenerateMojo m = new GenerateMojo();
        m.project = dir.resolve("missing").toFile();
        assertThatThrownBy(m::execute).isInstanceOf(MojoFailureException.class).hasMessageContaining("missing");
    }

    @Test
    void runWithoutK6FailsOrSkips() {
        RunMojo run = new RunMojo();
        run.suite = dir.toFile();
        run.k6 = dir.resolve("no-k6").toString();
        assertThatThrownBy(run::execute).isInstanceOf(MojoExecutionException.class).hasMessageContaining("k6");
        run.skipIfK6Missing = true;
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(run::execute);
    }

    private Path report(Path suite, String stamp, double p95, double failed) throws IOException {
        Path f = Files.createDirectories(suite.resolve("reports")).resolve("load-" + stamp + ".json");
        Files.writeString(f, """
                {"mode":"load","dataMode":"auto","baseUrl":"http://x","failedThresholds":[],"metrics":{},
                 "apis":[{"api":"getOrder","name":"GET /orders/{id}","requests":500,"failed":%s,"p95":%s}]}
                """.formatted(failed, p95));
        return f;
    }

    @Test
    void compareGatesRegressionsAndCanMoveTheBaseline() throws Exception {
        Path suite = dir.resolve("suite");
        Path baseline = report(suite, "2026-10-01T00-00-00-000Z", 100, 0);
        Path baselineCopy = Files.copy(baseline, dir.resolve("baseline.json"));

        report(suite, "2026-10-02T00-00-00-000Z", 105, 0); // within 20%
        CompareMojo ok = new CompareMojo();
        ok.suite = suite.toFile();
        ok.baseline = baselineCopy.toFile();
        ok.updateBaseline = true;
        ok.execute();
        assertThat(Files.readString(baselineCopy)).contains("\"p95\":105");
        assertThat(Files.readString(suite.resolve("reports/comparison-load.md"))).contains("No regression.");

        report(suite, "2026-10-03T00-00-00-000Z", 300, 0.2); // slower and failing
        CompareMojo bad = new CompareMojo();
        bad.suite = suite.toFile();
        bad.baseline = baselineCopy.toFile();
        assertThatThrownBy(bad::execute).isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("getOrder").hasMessageContaining("p95 105.0 → 300.0 ms");
        bad.failOnRegression = false;
        bad.execute(); // warns only
        assertThat(Files.readString(baselineCopy)).contains("\"p95\":105"); // never moved on a regression
    }

    @Test
    void runFailsTheBuildWhenThresholdsFail() throws Exception {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        Path suite = dir.resolve("suite");
        generate(project(), suite).execute();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            boolean broken = ex.getRequestURI().getPath().equals("/orders");
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(broken ? 500 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            RunMojo run = new RunMojo();
            run.suite = suite.toFile();
            run.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            run.env = Map.of("SEED", "false");
            run.api = "getOrder";
            run.execute(); // healthy API passes

            run.api = "listOrders"; // answers 500
            assertThatThrownBy(run::execute).isInstanceOf(MojoFailureException.class)
                    .hasMessageContaining("thresholds failed").hasMessageContaining("listOrders");
            run.failOnThresholds = false;
            run.execute();
            assertThat(List.of(suite.resolve("reports").toFile().list())).anyMatch(n -> n.startsWith("smoke-"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaGoalFailsClearlyWithoutADatabase() throws IOException {
        SchemaMojo m = new SchemaMojo();
        m.project = project().toFile(); // application.properties has no spring.datasource.url
        m.outFile = dir.resolve("schema.sql").toFile();

        assertThatThrownBy(m::execute).isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("no database configured");
        assertThat(dir.resolve("schema.sql")).doesNotExist();
    }

    @Test
    void schemaGoalWritesTheDdlOfTheConfiguredDatabase() throws Exception {
        String url = System.getenv("LOADTEST_IT_JDBC_URL");
        assumeThat(url).as("LOADTEST_IT_JDBC_URL (a PostgreSQL to read)").isNotNull();
        String user = System.getenv().getOrDefault("LOADTEST_IT_USER", "");
        String password = System.getenv().getOrDefault("LOADTEST_IT_PASSWORD", "");
        try (var c = java.sql.DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS mojo_schema_it CASCADE");
            st.execute("CREATE SCHEMA mojo_schema_it");
            st.execute("CREATE TABLE mojo_schema_it.pets (id bigserial PRIMARY KEY, name text NOT NULL)");
        }
        try {
            SchemaMojo m = new SchemaMojo();
            m.dbUrl = url;
            m.dbUser = user;
            m.dbPassword = password;
            m.dbSchema = "mojo_schema_it";
            m.outFile = dir.resolve("out/schema.sql").toFile();

            m.execute();

            assertThat(Files.readString(dir.resolve("out/schema.sql")))
                    .contains("CREATE TABLE mojo_schema_it.pets (").contains("PRIMARY KEY (id)");
        } finally {
            try (var c = java.sql.DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS mojo_schema_it CASCADE");
            }
        }
    }

    @Test
    void schemaGoalCanBeSkipped() throws Exception {
        SchemaMojo m = new SchemaMojo();
        m.skip = true;
        m.outFile = dir.resolve("schema.sql").toFile();

        m.execute();

        assertThat(dir.resolve("schema.sql")).doesNotExist();
    }
}
