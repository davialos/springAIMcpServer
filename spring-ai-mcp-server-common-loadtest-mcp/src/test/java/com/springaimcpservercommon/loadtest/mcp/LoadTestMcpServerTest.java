package com.springaimcpservercommon.loadtest.mcp;

import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The tools directly, and the server through a real MCP client over stdio. */
class LoadTestMcpServerTest {

    @TempDir
    Path root;

    private void project(String name) throws IOException {
        Path src = Files.createDirectories(root.resolve(name + "/src/main/java/shop"));
        Files.createDirectories(root.resolve(name + "/src/main/resources"));
        Files.writeString(root.resolve(name + "/src/main/resources/application.properties"), "server.port=8282\n");
        Files.writeString(src.resolve("OrderController.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    @GetMapping("/{id}") Object get(@PathVariable Long id) { return null; }
                    @PostMapping Object create(@RequestBody NewOrder o) { return null; }
                }
                record NewOrder(String product, int quantity) { }
                """);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult r) {
        assertThat(r.isError()).as(((McpSchema.TextContent) r.content().getFirst()).text()).isFalse();
        return (Map<String, Object>) r.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().getFirst()).text();
    }

    @Test
    void schemaWithoutADatasourceIsAnErrorTheAgentCanRead() throws IOException {
        project("shop"); // application.properties has no spring.datasource.url

        McpSchema.CallToolResult r = new LoadTestTools(root).schema(Map.of("project", "shop"));

        assertThat(r.isError()).isTrue();
        assertThat(text(r)).contains("no database configured");
    }

    @Test
    void schemaStaysInsideTheRoot() {
        McpSchema.CallToolResult r = new LoadTestTools(root).schema(Map.of("project", "../.."));

        assertThat(r.isError()).isTrue();
    }

    @Test
    void schemaReadsTheProjectsOwnDatabase() throws IOException, java.sql.SQLException {
        String url = System.getenv("LOADTEST_IT_JDBC_URL");
        assumeThat(url).as("LOADTEST_IT_JDBC_URL (a PostgreSQL to read)").isNotNull();
        String user = System.getenv().getOrDefault("LOADTEST_IT_USER", "");
        String password = System.getenv().getOrDefault("LOADTEST_IT_PASSWORD", "");
        project("shop");
        Files.writeString(root.resolve("shop/src/main/resources/application.properties"),
                "server.port=8282\nspring.datasource.url=" + url + "\nspring.datasource.username=" + user
                        + "\nspring.datasource.password=" + password + "\n");
        try (var c = java.sql.DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS mcp_schema_it CASCADE");
            st.execute("CREATE SCHEMA mcp_schema_it");
            st.execute("CREATE TABLE mcp_schema_it.pets (id bigserial PRIMARY KEY, name text NOT NULL)");
            st.execute("INSERT INTO mcp_schema_it.pets(name) VALUES ('Rex-the-secret-dog')");
        }
        try {
            Map<String, Object> out = structured(new LoadTestTools(root).schema(
                    Map.of("project", "shop", "schema", "mcp_schema_it", "rowCounts", true)));

            assertThat(out.get("ddl").toString()).contains("CREATE TABLE mcp_schema_it.pets (")
                    .contains("-- rows: 1").doesNotContain("Rex-the-secret-dog");
            assertThat(out.get("truncated")).isEqualTo(false);
            assertThat(out.get("tables").toString()).contains("name=pets", "kind=TABLE", "columns=2", "rows=1");
        } finally {
            try (var c = java.sql.DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS mcp_schema_it CASCADE");
            }
        }
    }

    @Test
    void discoverGenerateReportAndCompare() throws IOException {
        project("shop");
        LoadTestTools tools = new LoadTestTools(root);
        assertThat(tools.tools()).extracting(t -> t.tool().name()).containsExactly("loadtest_discover",
                "loadtest_generate", "loadtest_schema", "loadtest_run", "loadtest_report", "loadtest_compare",
                "loadtest_modes");
        assertThat(tools.tools()).filteredOn(t -> t.tool().name().equals("loadtest_run")).singleElement()
                .satisfies(t -> assertThat(t.tool().annotations().readOnlyHint()).isFalse());
        // reading the structure changes nothing, and it has no parameter that could name another database
        assertThat(tools.tools()).filteredOn(t -> t.tool().name().equals("loadtest_schema")).singleElement()
                .satisfies(t -> {
                    assertThat(t.tool().annotations().readOnlyHint()).isTrue();
                    assertThat(properties(t.tool())).containsOnlyKeys("project", "schema", "rowCounts");
                });

        Map<String, Object> d = structured(tools.discover(Map.of("project", "shop", "fields", true)));
        assertThat((List<?>) d.get("apis")).hasSize(2);
        assertThat(d.get("seedOrder").toString()).contains("orders");
        assertThat(d.get("fields").toString()).contains("NewOrder.product");

        McpSchema.CallToolResult g = tools.generate(Map.of("project", "shop",
                "values", Map.of("NewOrder.product", List.of("Espresso"))));
        assertThat(structured(g)).containsEntry("suite", "shop/load-tests").containsEntry("apis", 2)
                .containsEntry("baseUrl", "http://localhost:8282");
        assertThat(Files.readString(root.resolve("shop/load-tests/data/user.json"))).contains("Espresso");

        assertThat(tools.report(Map.of("suite", "shop/load-tests")).isError()).isTrue(); // no run yet
        Path reports = Files.createDirectories(root.resolve("shop/load-tests/reports"));
        String report = """
                {"mode":"smoke","dataMode":"auto","baseUrl":"http://x","failedThresholds":[],"metrics":{},
                 "apis":[{"api":"getOrder","name":"GET /orders/{id}","requests":40,"failed":%s,"p95":%s}]}
                """;
        Files.writeString(reports.resolve("smoke-2026-10-01T00-00-00-000Z.json"), report.formatted(0, 50));
        Files.writeString(reports.resolve("smoke-2026-10-02T00-00-00-000Z.json"), report.formatted(0.5, 51));
        assertThat(text(tools.report(Map.of("suite", "shop/load-tests")))).startsWith("smoke: 40 requests");
        Map<String, Object> c = structured(tools.compare(Map.of(
                "baseline", "shop/load-tests/reports/smoke-2026-10-01T00-00-00-000Z.json")));
        assertThat(c).containsEntry("passed", false).containsEntry("regressions", List.of("getOrder"));
        assertThat(structured(tools.modes()).get("dataModes").toString()).contains("mixed");
    }

    @Test
    void pathsStayInsideTheRoot() throws IOException {
        project("shop");
        LoadTestTools tools = new LoadTestTools(root.resolve("shop"));
        assertThat(text(tools.discover(Map.of("project", "../")))).contains("must be inside");
        assertThat(text(tools.generate(Map.of("project", ".", "outDir", "/tmp/elsewhere")))).contains("must be inside");
        Files.createSymbolicLink(root.resolve("shop/escape"), root);
        assertThat(text(tools.discover(Map.of("project", "escape")))).contains("symbolic link");
        assertThat(text(tools.discover(Map.of()))).contains("project is required");
        assertThat(tools.discover(Map.of("project", "nope")).isError()).isTrue();
    }

    @Test
    void runsK6ThroughTheTool() throws IOException {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        project("shop");
        LoadTestTools tools = new LoadTestTools(root);
        structured(tools.generate(Map.of("project", "shop")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out = "{\"id\":7}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestMethod().equals("POST") ? 201 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            McpSchema.CallToolResult r = tools.run(Map.of("suite", "shop/load-tests", "mode", "smoke",
                    "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(), "env", Map.of("SEED_PER_TABLE", 1)));
            Map<String, Object> s = structured(r);
            assertThat(text(r)).startsWith("PASSED — smoke");
            assertThat(s).containsEntry("passed", true);
            assertThat(s.get("report").toString()).contains("getOrder").contains("createOrder");
            assertThat(s.get("outputTail").toString()).contains("seed: orders 1/1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void servesTheToolsOverStdio() throws IOException {
        project("shop");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters params = ServerParameters.builder(java)
                .args("-cp", System.getProperty("java.class.path"), LoadTestMcpServer.class.getName(),
                        "--root", root.toString())
                .build();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        try (McpSyncClient client = McpClient.sync(new StdioClientTransport(params, mapper))
                .requestTimeout(Duration.ofSeconds(60)).build()) {
            McpSchema.InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("spring-loadtest");
            assertThat(init.instructions()).contains("loadtest_discover");
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .contains("loadtest_discover", "loadtest_run", "loadtest_compare");
            McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest("loadtest_discover",
                    Map.of("project", "shop")));
            assertThat(r.isError()).isFalse();
            assertThat(text(r)).startsWith("2 APIs");
            McpSchema.CallToolResult outside = client.callTool(new McpSchema.CallToolRequest("loadtest_discover",
                    Map.of("project", "/")));
            assertThat(outside.isError()).isTrue();
        }
    }
}
