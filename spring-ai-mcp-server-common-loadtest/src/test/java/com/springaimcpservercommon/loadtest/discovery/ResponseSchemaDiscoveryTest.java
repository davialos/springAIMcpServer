package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a successful response looks like, discovered from OpenAPI, Java return types and recorded traffic. */
class ResponseSchemaDiscoveryTest {

    @TempDir
    static Path dir;
    private static ApiCatalog source;

    @BeforeAll
    static void scan() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("DemoController.java"), """
                package demo;
                import com.fasterxml.jackson.annotation.JsonIgnore;
                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                class DemoController {
                    @GetMapping("/orders/{id}") ResponseEntity<OrderView> get(@PathVariable Long id) { return null; }
                    @GetMapping("/orders") Page<OrderView> list() { return null; }
                    @GetMapping("/tags") List<String> tags() { return null; }
                    @GetMapping("/wrapped") ApiResponse<OrderView> wrapped() { return null; }
                    @GetMapping("/text") String text() { return null; }
                    @PostMapping("/orders") ResponseEntity<Void> create(@RequestBody NewOrder o) { return null; }
                    @GetMapping("/odd") Odd odd() { return null; }
                    @GetMapping("/maybe") Optional<LineView> maybe() { return null; }
                }
                record NewOrder(String product, int quantity) { }
                record OrderView(Long id, int quantity, boolean paid, java.math.BigDecimal total, OrderStatus status,
                                 java.time.Instant placedAt, List<LineView> lines, String note) { }
                record LineView(String sku, double price) { }
                class ApiResponse<T> { private T data; private boolean success; private String message; }
                enum OrderStatus { NEW, PAID }
                class Odd {
                    @JsonProperty("n") private int count;
                    private int plain;
                    @JsonIgnore private String hidden;
                    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) private String password;
                    @JsonProperty("x") public String getX() { return null; }
                }
                """);
        source = new SpringSourceScanner(new ArrayList<>()::add).scan(dir);
    }

    private static JsonNode response(ApiCatalog c, String idOrRoute) {
        return c.endpoints().stream().filter(e -> e.id().equals(idOrRoute) || e.displayName().equals(idOrRoute))
                .findFirst().orElseThrow(() -> new AssertionError(idOrRoute + " in " + c.endpoints()))
                .responseSchema();
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void javaReturnTypesBecomeLenientSchemasWithWrappersLookedThrough() {
        JsonNode order = response(source, "GET /api/orders/{id}");
        assertThat(order.path("type").asString()).isEqualTo("object");
        JsonNode p = order.path("properties");
        assertThat(p.path("id").path("type").asString()).isEqualTo("integer");
        assertThat(names(p.path("total").path("type"))).as("BigDecimal may be a number or a string")
                .containsExactly("number", "string");
        assertThat(names(p.path("status").path("enum"))).containsExactly("NEW", "PAID");
        assertThat(names(p.path("placedAt").path("type"))).contains("string", "number", "array");
        assertThat(p.path("lines").path("type").asString()).isEqualTo("array");
        assertThat(p.path("lines").path("items").path("properties").path("sku").path("type").asString())
                .isEqualTo("string");
        // only Java primitives are always serialised; boxed members and references may be absent or null
        assertThat(names(order.path("required"))).containsExactlyInAnyOrder("quantity", "paid");
    }

    @Test
    void pagesCollectionsOptionalsAndGenericDtosAreUnderstood() {
        JsonNode page = response(source, "GET /api/orders");
        assertThat(page.path("type").asString()).isEqualTo("object");
        assertThat(page.path("properties").path("content").path("type").asString()).isEqualTo("array");
        assertThat(page.path("properties").path("content").path("items").path("properties").has("quantity")).isTrue();
        assertThat(response(source, "GET /api/tags").path("items").path("type").asString()).isEqualTo("string");
        JsonNode maybe = response(source, "GET /api/maybe");
        assertThat(maybe.path("nullable").asBoolean()).isTrue();
        assertThat(maybe.path("properties").path("price").path("type").asString()).isEqualTo("number");
        JsonNode wrapped = response(source, "GET /api/wrapped");
        assertThat(names(wrapped.path("required"))).containsExactlyInAnyOrder("success");
        assertThat(wrapped.path("properties").path("data").path("properties").path("quantity").path("type").asString())
                .as("the type argument is bound to the generic field").isEqualTo("integer");
    }

    @Test
    void nonJsonAndEmptyResponsesHaveNoSchema() {
        assertThat(response(source, "GET /api/text")).as("String may be plain text").isNull();
        assertThat(response(source, "POST /api/orders")).as("ResponseEntity<Void>").isNull();
    }

    @Test
    void customisedSerialisationDropsRequiredMembersAndHiddenFields() {
        JsonNode odd = response(source, "GET /api/odd");
        assertThat(odd.path("properties").has("n")).isTrue();
        assertThat(odd.path("properties").has("hidden")).isFalse();
        assertThat(odd.path("properties").has("password")).as("write-only").isFalse();
        assertThat(odd.has("required")).as("a renamed getter makes the field list unreliable").isFalse();
    }

    private static final String SPEC = """
            {"openapi":"3.0.3","info":{"title":"t"},"paths":{
              "/orders/{id}":{"get":{"operationId":"getOrder","responses":{
                "default":{"description":"x"},
                "200":{"description":"ok","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}},
                "404":{"description":"no"}}}},
              "/orders":{"post":{"operationId":"createOrder","responses":{
                "201":{"description":"made","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}},
                "202":{"description":"queued","content":{"application/json":{"schema":{"type":"string"}}}}}},
                "get":{"operationId":"listOrders","responses":{"200":{"description":"ok","content":{"application/json":{
                  "schema":{"type":"array","items":{"$ref":"#/components/schemas/Order"}}}}}}}},
              "/ping":{"get":{"operationId":"ping","responses":{"204":{"description":"none"}}}},
              "/union":{"get":{"operationId":"union","responses":{"200":{"description":"ok","content":{"application/json":{
                "schema":{"oneOf":[{"type":"string"},{"type":"integer"}]}}}}}}}
            },"components":{"schemas":{
              "Base":{"type":"object","required":["id"],"properties":{"id":{"type":"integer","format":"int64","readOnly":true}}},
              "Order":{"allOf":[{"$ref":"#/components/schemas/Base"},{"type":"object","required":["status"],"properties":{
                "status":{"type":"string","enum":["NEW","PAID"]},
                "note":{"type":"string","nullable":true},
                "password":{"type":"string","writeOnly":true},
                "parent":{"$ref":"#/components/schemas/Order"},
                "lines":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}}}}}}]}}}}
            """;

    @Test
    void openApiResponsesInlineReferencesKeepReadOnlyMembersAndStopAtCycles() {
        ApiCatalog c = new OpenApiReader(new ArrayList<>()::add).read(SPEC);
        JsonNode order = response(c, "getOrder");
        assertThat(order.path("type").asString()).isEqualTo("object");
        assertThat(names(order.path("required"))).containsExactlyInAnyOrder("id", "status");
        assertThat(order.path("properties").path("id").path("type").asString()).as("readOnly stays in a response")
                .isEqualTo("integer");
        assertThat(names(order.path("properties").path("status").path("enum"))).containsExactly("NEW", "PAID");
        assertThat(order.path("properties").path("note").path("nullable").asBoolean()).isTrue();
        assertThat(order.path("properties").has("password")).as("writeOnly").isFalse();
        assertThat(order.path("properties").path("parent").isEmpty()).as("a cycle is not followed").isTrue();
        assertThat(response(c, "createOrder").path("type").asString()).as("the lowest 2xx with JSON wins")
                .isEqualTo("object");
        assertThat(response(c, "listOrders").path("items").path("properties").has("status")).isTrue();
        assertThat(response(c, "ping")).isNull();
        assertThat(response(c, "union")).as("a oneOf says nothing reliable").isNull();
    }

    @Test
    void recordedResponsesAreInferredAndMergedAcrossCalls() {
        JsonNode a = ResponseSchemas.infer(Documents.parse("{\"id\":1,\"name\":\"a\",\"tags\":[\"x\"],\"nick\":null}"));
        JsonNode b = ResponseSchemas.infer(Documents.parse("{\"id\":2.5,\"name\":\"b\",\"extra\":true,\"nick\":\"n\"}"));
        JsonNode m = ResponseSchemas.merge(a, b);
        assertThat(m.path("properties").path("id").path("type").asString()).as("integer + number = number")
                .isEqualTo("number");
        assertThat(names(m.path("required"))).as("only what every call returned").containsExactlyInAnyOrder("id", "name");
        assertThat(m.path("properties").path("nick").path("type").asString()).isEqualTo("string");
        assertThat(m.path("properties").path("nick").path("nullable").asBoolean()).isTrue();
        assertThat(ResponseSchemas.merge(ResponseSchemas.infer(Documents.parse("{\"v\":1}")),
                ResponseSchemas.infer(Documents.parse("{\"v\":\"s\"}"))).path("properties").path("v").isEmpty())
                .as("a type conflict falls back to anything").isTrue();

        HarCapture har = new HarReader(new ArrayList<>()::add).read(Fixtures.sampleShopHar(),
                new HarReader.Options(List.of(), "/shop", List.of()));
        ApiEndpoint customer = har.catalog().endpoints().stream()
                .filter(e -> e.path().equals("/api/v1/customers/{customerId}") && e.method().name().equals("GET"))
                .findFirst().orElseThrow();
        assertThat(customer.responseSchema().path("properties").path("id").path("type").asString())
                .isEqualTo("integer");
    }
}
