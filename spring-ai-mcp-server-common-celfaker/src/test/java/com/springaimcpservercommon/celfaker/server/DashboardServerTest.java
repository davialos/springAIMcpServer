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
}
