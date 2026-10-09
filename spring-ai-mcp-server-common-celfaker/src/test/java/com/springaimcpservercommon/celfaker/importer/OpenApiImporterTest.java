package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.contract.ApiRole;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.data.ApiData;
import com.springaimcpservercommon.celfaker.data.FakeInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiImporterTest {

    static final String OAS3 = """
            openapi: 3.0.3
            info: {title: Shop API}
            servers: [{url: 'https://shop.example.com/api'}]
            security: [{bearer: []}]
            components:
              securitySchemes:
                bearer: {type: http, scheme: bearer}
              schemas:
                Address:
                  type: object
                  required: [city]
                  properties:
                    city: {type: string, minLength: 2, maxLength: 40}
                    zip: {type: string, pattern: '^[0-9]{5}$'}
                Order:
                  type: object
                  required: [quantity]
                  properties:
                    id: {type: integer, readOnly: true}
                    quantity: {type: integer, minimum: 1, maximum: 100}
                    price: {type: number, minimum: 0.5, maximum: 999.99}
                    status: {type: string, enum: [NEW, PAID]}
                    email: {type: string, format: email}
                    placedAt: {type: string, format: date-time}
                    tags: {type: array, minItems: 1, maxItems: 5, items: {type: string}}
                    address: {$ref: '#/components/schemas/Address'}
            paths:
              /orders:
                post:
                  operationId: createOrder
                  summary: Create order
                  description: Creates an order.
                  requestBody:
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/Order'}
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema: {type: object, properties: {id: {type: integer, example: 5001}}}
                    '422': {description: invalid}
              /orders/{id}/validate:
                get:
                  summary: Validate order
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}}
                    - {name: strict, in: query, required: true, schema: {type: boolean}}
                  responses:
                    '200': {description: ok, content: {application/json: {schema: {type: object, properties: {valid: {type: boolean}}}}}}
            """;

    @Test
    void importsOpenApi3WithExamplesRulesAndSecurity() {
        Imported i = OpenApiImporter.parse(SpecFetcher.parse(OAS3), "");
        assertThat(i.title()).isEqualTo("Shop API");
        assertThat(i.baseUrl()).isEqualTo("https://shop.example.com");
        ApiSpec create = i.apis().stream().filter(a -> a.id().equals("createOrder")).findFirst().orElseThrow();
        assertThat(create.path()).isEqualTo("/api/orders");
        assertThat(create.headers()).containsEntry("Authorization", "Bearer {{env.TOKEN}}");
        assertThat(create.expectedStatus()).containsExactly(201);
        assertThat(create.invalidStatus()).containsExactly(422);
        assertThat(create.responseExample().path("id").asInt()).isEqualTo(5001);
        assertThat(create.requestExample().has("id")).as("read-only fields are not sent").isFalse();
        assertThat(create.requestExample().path("quantity").asInt()).isBetween(1, 100);
        assertThat(create.requestExample().path("status").asString()).isEqualTo("NEW");
        assertThat(create.requestExample().path("address").path("city").asString()).hasSizeBetween(2, 40);
        assertThat(create.rules()).contains("createOrder.quantity >= 1", "createOrder.quantity <= 100",
                "createOrder.price >= 0.5", "createOrder.status in [\"NEW\", \"PAID\"]", "address.city.size() >= 2",
                "createOrder.tags.size() <= 5");
        ApiSpec validate = i.apis().stream().filter(a -> a.path().contains("validate")).findFirst().orElseThrow();
        assertThat(validate.role()).isEqualTo(ApiRole.VALIDATION);
        assertThat(validate.path()).isEqualTo("/api/orders/{id}/validate?strict=true");
    }

    @Test
    void importedRulesAreSatisfiedByTheFakeInput() {
        ApiSpec create = OpenApiImporter.parse(SpecFetcher.parse(OAS3), "").apis().getFirst();
        ApiData data = FakeInput.generate(create, 5, 8);
        assertThat(data.valid()).hasSize(8);
        data.valid().forEach(b -> {
            assertThat(b.path("quantity").asInt()).isBetween(1, 100);
            assertThat(b.path("status").asString()).isIn("NEW", "PAID");
        });
        assertThat(data.invalid()).anyMatch(c -> c.reason().startsWith("rule:"));
        assertThat(data.invalid()).anyMatch(c -> c.reason().equals("createOrder.quantity:missing"));
    }

    @Test
    void importsSwagger2() {
        Imported i = OpenApiImporter.parse(SpecFetcher.parse("""
                {"swagger":"2.0","info":{"title":"Pets"},"host":"pets.example.com:9000","basePath":"/v2","schemes":["https"],
                 "securityDefinitions":{"key":{"type":"apiKey","in":"header","name":"X-Key"}},
                 "paths":{"/pets":{"post":{"operationId":"addPet","security":[{"key":[]}],
                   "parameters":[{"in":"body","name":"body","schema":{"$ref":"#/definitions/Pet"}}],
                   "responses":{"200":{"description":"ok","schema":{"$ref":"#/definitions/Pet"}},"400":{"description":"bad"}}}}},
                 "definitions":{"Pet":{"type":"object","properties":{"name":{"type":"string","maxLength":5},"age":{"type":"integer","minimum":0}}}}}
                """), "");
        assertThat(i.baseUrl()).isEqualTo("https://pets.example.com:9000");
        ApiSpec api = i.apis().getFirst();
        assertThat(api.path()).isEqualTo("/v2/pets");
        assertThat(api.headers()).containsEntry("X-Key", "{{env.API_KEY}}");
        assertThat(api.requestExample().path("name").asString()).hasSizeLessThanOrEqualTo(5);
        assertThat(api.invalidStatus()).containsExactly(400);
        assertThat(api.rules()).contains("addPet.age >= 0", "addPet.name.size() <= 5");
    }

    @Test
    void survivesRecursiveSchemas() {
        Imported i = OpenApiImporter.parse(SpecFetcher.parse("""
                {"openapi":"3.0.0","info":{"title":"t"},"paths":{"/n":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}},"responses":{"200":{"description":"x"}}}}},
                 "components":{"schemas":{"Node":{"type":"object","properties":{"value":{"type":"integer"},"next":{"$ref":"#/components/schemas/Node"}}}}}}
                """), "http://svc:8080/v3/api-docs");
        assertThat(i.apis()).hasSize(1);
        assertThat(i.baseUrl()).isEqualTo("http://svc:8080");
    }

    @Test
    void rejectsNonSpecs() {
        assertThatThrownBy(() -> SpecFetcher.parse("{\"hello\":1}")).isInstanceOf(IllegalArgumentException.class);
    }
}
