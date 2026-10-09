package com.springaimcpservercommon.celfaker.server;

import com.springaimcpservercommon.celfaker.payload.JsonValues;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardServerTest {

    static DashboardServer server;
    static HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        server = new DashboardServer(0);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private HttpResponse<byte[]> post(String path, String json, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode json(HttpResponse<byte[]> r) {
        return JsonValues.MAPPER.readTree(r.body());
    }

    @Test
    void servesTheDashboard() throws Exception {
        HttpResponse<String> index = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("CEL Faker");
        HttpResponse<String> js = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/view-flow.js")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/javascript"));
        HttpResponse<String> runtime = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/runtime.js")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(runtime.statusCode()).isEqualTo(200);
        assertThat(runtime.body()).contains("export function createRuntime");
        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/nope.js")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    @Test
    void analyzeThenGenerateExpressionsAndCases() throws Exception {
        JsonNode analysis = json(post("/api/analyze", """
                {"payload": {"age": 34, "email": "a@b.co", "tags": ["x"]}, "object": "customer"}""", "application/json"));
        assertThat(analysis.path("candidates")).hasSize(3);
        assertThat(analysis.path("candidates").get(0).path("name").asString()).isEqualTo("customer.age");

        String cands = """
                [{"path":"age","objectCode":"customer","attributeCode":"age","type":"INT","sample":34},
                 {"path":"email","objectCode":"customer","attributeCode":"email","type":"STRING","sample":"a@b.co"}]""";
        JsonNode exprs = json(post("/api/expressions", "{\"candidates\":" + cands + ",\"seed\":3,\"options\":{\"categories\":[\"COMPARISON\",\"MACRO\"],\"maxPerParameter\":0,\"combined\":0}}", "application/json"));
        assertThat(exprs.path("expressions").size()).isGreaterThan(10);
        assertThat(exprs.path("rejected")).isEmpty();
        assertThat(exprs.path("attributeMap").path("attributes").has("customer.age")).isTrue();

        JsonNode cases = json(post("/api/cases", "{\"candidates\":" + cands + ",\"expression\":\"customer.age >= 18\",\"max\":50}", "application/json"));
        Set<String> results = new HashSet<>();
        cases.path("cases").forEach(c -> results.add(c.path("expected").asString()));
        assertThat(results).containsExactlyInAnyOrder("true", "false");
    }

    @Test
    void generatesTheSuiteAsJsonAndZip() throws Exception {
        String contract;
        try (var in = getClass().getResourceAsStream("/celfaker/examples/shop-contract.json")) {
            contract = new String(in.readAllBytes());
        }
        String body = "{\"contract\":" + contract + ",\"casesPerExpression\":2,\"validCount\":5}";
        JsonNode out = json(post("/api/generate", body, "application/json"));
        assertThat(out.path("files").has("k6/main.js")).isTrue();
        assertThat(out.path("summary").path("parameters").asInt()).isGreaterThan(5);

        HttpResponse<byte[]> zip = post("/api/generate.zip", body, "application/json");
        assertThat(zip.headers().firstValue("Content-Type")).hasValue("application/zip");
        Set<String> names = new HashSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).contains("k6/main.js", "k6/lib/runtime.js", "attribute-map.json");
    }

    @Test
    void reportsBadInputAsClientErrors() throws Exception {
        assertThat(post("/api/analyze", "{not json", "application/json").statusCode()).isEqualTo(400);
        assertThat(post("/api/workflow/validate", "{\"contract\":{\"apis\":[]},\"workflow\":{\"steps\":[{\"id\":\"a\",\"api\":\"x\"}]}}", "application/json").statusCode()).isEqualTo(200);
    }

    @Test
    void refusesCrossSitePostsAndForeignHosts() throws Exception {
        assertThat(post("/api/analyze", "{}", "text/plain").statusCode()).isEqualTo(415);
        assertThat(post("/api/analyze", "{}", "application/x-www-form-urlencoded").statusCode()).isEqualTo(415);
        // a rebinding page reaches the loopback port under its own host name
        try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes());
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();
            assertThat(status).contains("403");
        }
    }

    @Test
    void importsCurlAndOpenApiFromAUrlThenFakesInputAndSends() throws Exception {
        JsonNode curl = json(post("/api/import/curl", "{\"curl\":\"curl -X POST http://localhost:1/orders -H 'Authorization: Bearer x' -d '{\\\"qty\\\":3}'\"}", "application/json"));
        assertThat(curl.path("apis").get(0).path("headers").path("Authorization").asString()).isEqualTo("Bearer {{env.AUTHORIZATION}}");

        // a tiny service that serves its Swagger document and echoes posted bodies
        com.sun.net.httpserver.HttpServer svc = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        svc.createContext("/v3/api-docs", ex -> {
            byte[] doc = ("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"svc\"},\"paths\":{\"/echo\":{\"post\":{\"operationId\":\"echo\","
                    + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":9}}}}}},"
                    + "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, doc.length);
            ex.getResponseBody().write(doc);
            ex.close();
        });
        svc.createContext("/echo", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] out = ("{\"got\":" + new String(in) + ",\"auth\":\"" + auth + "\"}").getBytes();
            ex.sendResponseHeaders(201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        svc.start();
        try {
            String origin = "http://localhost:" + svc.getAddress().getPort();
            // the address is only the service root: the importer finds the document itself
            JsonNode imported = json(post("/api/import/openapi", "{\"url\":\"" + origin + "/swagger-ui/index.html\"}", "application/json"));
            assertThat(imported.path("baseUrl").asString()).isEqualTo(origin);
            JsonNode api = imported.path("apis").get(0);
            assertThat(api.path("id").asString()).isEqualTo("echo");

            JsonNode fake = json(post("/api/fake", "{\"api\":" + api + ",\"count\":4,\"seed\":1}", "application/json"));
            assertThat(fake.path("valid")).hasSize(4);
            fake.path("valid").forEach(b -> assertThat(b.path("n").asInt()).isBetween(1, 9));

            JsonNode sent = json(post("/api/send", "{\"method\":\"POST\",\"url\":\"" + origin + "/echo\",\"headers\":{\"Authorization\":\"Bearer {{env.TOKEN}}\"},"
                    + "\"body\":" + fake.path("valid").get(0) + ",\"env\":{\"TOKEN\":\"t0k\"}}", "application/json"));
            assertThat(sent.path("status").asInt()).isEqualTo(201);
            assertThat(sent.path("body").asString()).contains("Bearer t0k");
            assertThat(sent.path("unresolvedEnv")).isEmpty();
            JsonNode unresolved = json(post("/api/send", "{\"method\":\"GET\",\"url\":\"" + origin + "/nothing\",\"headers\":{\"X\":\"{{env.MISSING}}\"}}", "application/json"));
            assertThat(unresolved.path("unresolvedEnv").get(0).asString()).isEqualTo("MISSING");
        } finally {
            svc.stop(0);
        }
        assertThat(post("/api/send", "{\"method\":\"GET\",\"url\":\"file:///etc/passwd\"}", "application/json").statusCode()).isEqualTo(400);
        assertThat(post("/api/import/openapi", "{}", "application/json").statusCode()).isEqualTo(400);
    }

    @Test
    void embedsInLocalDevAndListsLocalServices() throws Exception {
        try (DashboardServer embedded = new DashboardServer(0, List.of("http://127.0.0.1:8765", "http://localhost:8765"),
                List.of(new DashboardServer.LocalService("orders", "http://localhost:8081")))) {
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + embedded.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v ->
                    assertThat(v).contains("frame-ancestors http://127.0.0.1:8765 http://localhost:8765"));
            HttpResponse<String> svc = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + embedded.port() + "/api/local-services")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(JsonValues.MAPPER.readTree(svc.body()).path("services").get(0).path("url").asString()).isEqualTo("http://localhost:8081");
        }
        // the default refuses framing, and only loopback origins can be allowed
        HttpResponse<String> plain = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(plain.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v).contains("frame-ancestors 'none'"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DashboardServer(0, List.of("https://evil.example"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparesAScenarioForTheBrowserRunner() throws Exception {
        String contract;
        try (var in = getClass().getResourceAsStream("/celfaker/examples/shop-contract.json")) {
            contract = new String(in.readAllBytes());
        }
        String wf = "{\"name\":\"s\",\"steps\":[{\"id\":\"c\",\"api\":\"createCustomer\",\"extract\":[{\"name\":\"id\",\"from\":\"body.id\"}],"
                + "\"assertions\":[{\"from\":\"status\",\"op\":\"==\",\"value\":\"201\"}]}]}";
        JsonNode ok = json(post("/api/scenario/prepare", "{\"contract\":" + contract + ",\"workflow\":" + wf + ",\"count\":6}", "application/json"));
        assertThat(ok.path("problems")).isEmpty();
        assertThat(ok.path("valid").path("createCustomer")).hasSize(6);
        assertThat(ok.path("invalid").path("createCustomer").size()).isGreaterThan(5);
        assertThat(ok.path("apis").path("createCustomer").path("hasBody").asBoolean()).isTrue();
        assertThat(ok.path("apis").has("createOrder")).as("only APIs the scenario uses").isFalse();
        assertThat(ok.path("workflow").path("steps").get(0).path("assertions").get(0).path("op").asString()).isEqualTo("==");
        JsonNode bad = json(post("/api/scenario/prepare", "{\"contract\":" + contract + ",\"workflow\":{\"name\":\"s\",\"steps\":[{\"id\":\"x\",\"api\":\"ghost\"}]}}", "application/json"));
        assertThat(bad.path("problems").get(0).asString()).contains("unknown api ghost");
    }
}
