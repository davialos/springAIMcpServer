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
        assertThat(out()).contains("spike").contains("mixed-stress").contains("preview").contains("DATA_MODE");
    }
}
