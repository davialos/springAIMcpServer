package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String stdin, String... args) {
        return new LoadTestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8))).execute(args);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void discoverListsApisAndFieldPlans() {
        int code = run("", "discover", "--project", Fixtures.sampleShop().toString(), "--json");
        assertThat(code).as(err()).isZero();
        assertThat(out()).contains("getCustomer").contains("/api/v1/orders/{orderId}")
                .contains("getCustomer.path.id  kind=id  real=customers.id")
                .contains("CreateCustomerRequest.password  kind=password  sensitive");
    }

    @Test
    void generateTakesUserValuesBindingsAndInteractiveInput(@TempDir Path dir) throws IOException {
        Path values = dir.resolve("values.json");
        Files.writeString(values, "{\"payloads\": {\"createOrder\": [{\"customerId\": 1, \"lines\": [{\"productSku\": \"SKU-1\"}]}]}}");
        Path suite = dir.resolve("suite");
        // Interactive: skip every API except getProduct, answer its single field.
        String answers = "n\n".repeat(8) + "y\nSKU-7, SKU-8\n";
        int code = run(answers, "generate", "--project", Fixtures.sampleShop().toString(), "--out", suite.toString(),
                "--no-db", "--user-data", values.toString(), "--value", "email=qa@example.com,qa2@example.com",
                "--bind", "*.deliveryNotes=orders.status", "--interactive", "--data-mode", "dummy",
                "--include", "/api/v1/**", "--exclude", "DELETE /**");
        assertThat(code).as(err()).isZero();
        assertThat(out()).contains("Generated k6 suite").contains("9 APIs");

        JsonNode user = Documents.parse(Files.readString(suite.resolve("data/user.json")));
        assertThat(user.path("fields").path("email").toString()).contains("qa@example.com", "qa2@example.com");
        assertThat(user.path("fields").path("getProduct.path.sku").toString()).contains("SKU-7", "SKU-8");
        assertThat(user.path("payloads").path("createOrder").isArray()).isTrue();
        assertThat(user.path("bindings").path("*.deliveryNotes").asString()).isEqualTo("orders.status");

        JsonNode config = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        assertThat(config.path("baseUrl").asString()).isEqualTo("http://localhost:8081/shop");
        assertThat(config.path("data").path("mode").asString()).isEqualTo("dummy");
        assertThat(config.path("apis").has("deleteCustomer")).isFalse();
        assertThat(Files.readString(suite.resolve("providers/schemas.js")))
                .contains("\"key\":\"CreateOrderRequest.deliveryNotes\"").contains("\"real\":\"orders.status\"");
    }

    @Test
    void regenerationKeepsUserValuesWithoutDuplicates(@TempDir Path dir) throws IOException {
        Path suite = dir.resolve("suite");
        String[] args = {"generate", "--project", Fixtures.sampleShop().toString(), "--out", suite.toString(),
                "--no-db", "--value", "email=qa@example.com"};
        assertThat(run("", args)).as(err()).isZero();
        assertThat(run("", "generate", "--project", Fixtures.sampleShop().toString(), "--out", suite.toString(),
                "--no-db", "--value", "email=qa@example.com,qa2@example.com")).as(err()).isZero();
        JsonNode user = Documents.parse(Files.readString(suite.resolve("data/user.json")));
        assertThat(user.path("fields").path("email").toString()).isEqualTo("[\"qa@example.com\",\"qa2@example.com\"]");
    }

    @Test
    void generatesFromABrowserRecordingAlone(@TempDir Path dir) throws IOException {
        Path har = dir.resolve("shop.har");
        Files.writeString(har, Fixtures.sampleShopHar());
        Path suite = dir.resolve("suite");
        int code = run("", "generate", "--har", har.toString(), "--out", suite.toString(), "--no-db");
        assertThat(code).as(err()).isZero();
        assertThat(err()).contains("journey of 10 steps").contains("sensitive fields never kept");
        JsonNode config = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        assertThat(config.path("baseUrl").asString()).isEqualTo("https://shop.local:8443");
        assertThat(config.path("apis").has("getCustomersById")).isTrue();
        assertThat(config.path("apis").has("getShopCustomersById")).isFalse();
        assertThat(Documents.parse(Files.readString(suite.resolve("data/journey.json"))).size()).isEqualTo(10);
        assertThat(Files.readString(suite.resolve("README.md"))).contains("journey-spike");
        assertThat(Files.readString(suite.resolve("data/user.json"))).doesNotContain("S3cret!pw");

        out.reset();
        assertThat(run("", "discover", "--har", har.toString())).isZero();
        assertThat(out()).contains("10 recorded calls replayable as a journey");
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertThat(run("", "generate")).isEqualTo(2);
        assertThat(err()).contains("--project");
        assertThat(run("", "bogus")).isEqualTo(2);
        assertThat(run("", "discover", "--project")).isEqualTo(2);
        assertThat(run("", "run", "--suite", "/nonexistent")).isEqualTo(2);
    }

    @Test
    void modesListsEveryLoadAndDataMode() {
        assertThat(run("", "modes")).isZero();
        assertThat(out()).contains("spike").contains("mixed-stress").contains("journey-spike").contains("preview")
                .contains("DATA_MODE");
    }

    @Test
    void initGradleWritesTheScriptOnce(@TempDir Path dir) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }\n");
        assertThat(run("", "init-gradle", "--project", dir.toString())).isZero();
        Path script = dir.resolve("gradle/loadtest.gradle");
        assertThat(java.nio.file.Files.readString(script)).contains("loadtestGenerate").contains("loadtestRun")
                .contains("JavaLanguageVersion.of(loadtest.javaVersion)");
        assertThat(out()).contains("apply(from = \"gradle/loadtest.gradle\")");

        java.nio.file.Files.writeString(script, "// mine\n");
        assertThat(run("", "init-gradle", "--project", dir.toString())).isZero();
        assertThat(java.nio.file.Files.readString(script)).isEqualTo("// mine\n");
        assertThat(run("", "init-gradle", "--project", dir.toString(), "--force")).isZero();
        assertThat(java.nio.file.Files.readString(script)).contains("loadtestCompare");
    }

    @Test
    void reportAndCompareCommands(@TempDir Path dir) throws IOException {
        Path reports = java.nio.file.Files.createDirectories(dir.resolve("reports"));
        String report = """
                {"mode":"smoke","dataMode":"auto","baseUrl":"http://x","failedThresholds":[],"metrics":{},
                 "apis":[{"api":"getOrder","name":"GET /orders/{id}","requests":50,"failed":0,"p95":%s}]}
                """;
        Path base = java.nio.file.Files.writeString(reports.resolve("smoke-2026-10-01T00-00-00-000Z.json"),
                report.formatted(100));
        java.nio.file.Files.writeString(reports.resolve("smoke-2026-10-02T00-00-00-000Z.json"), report.formatted(400));
        assertThat(run("", "report", "--suite", dir.toString())).isZero();
        assertThat(out()).contains("smoke-2026-10-02").contains("getOrder").contains("400.0");
        assertThat(run("", "compare", "--suite", dir.toString(), "--baseline", base.toString())).isEqualTo(3);
        assertThat(out()).contains("**regression**").contains("p95 100.0 → 400.0 ms");
        assertThat(run("", "compare", "--suite", dir.toString(), "--baseline", base.toString(),
                "--max-p95-increase", "500")).isZero();
        assertThat(run("", "compare", "--suite", dir.toString())).isEqualTo(2); // --baseline missing
    }
}
