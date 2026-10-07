package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiReaderTest {

    private static final String DOC = """
            {
              "openapi": "3.1.0",
              "info": {"title": "pets", "version": "1"},
              "servers": [{"url": "http://localhost:9000/petstore"}],
              "paths": {
                "/pets/{petId}": {
                  "parameters": [{"$ref": "#/components/parameters/PetId"}],
                  "get": {"operationId": "getPet", "tags": ["pets"], "summary": "One pet"},
                  "put": {"operationId": "updatePet",
                          "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Pet"}}}}}
                },
                "/pets": {
                  "get": {"parameters": [
                      {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
                      {"name": "Authorization", "in": "header", "schema": {"type": "string"}},
                      {"name": "session", "in": "cookie", "schema": {"type": "string"}}]},
                  "post": {"operationId": "createPet", "requestBody": {"$ref": "#/components/requestBodies/NewPet"}}
                },
                "/pets/{petId}/photo": {
                  "post": {"operationId": "uploadPhoto",
                           "requestBody": {"content": {"multipart/form-data": {"schema": {"type": "object",
                             "required": ["file"], "properties": {"file": {"type": "string", "format": "binary"},
                             "caption": {"type": "string"}}}}}}}
                },
                "/pets/{petId}/raw": {
                  "put": {"operationId": "rawBytes",
                          "requestBody": {"content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}}}
                },
                "/pets/search": {
                  "post": {"operationId": "searchPets",
                           "requestBody": {"content": {"application/x-www-form-urlencoded": {"schema": {"type": "object",
                             "properties": {"q": {"type": "string"}, "limit": {"type": "integer"}}}}}}}
                }
              },
              "components": {
                "parameters": {"PetId": {"name": "petId", "in": "path", "required": true, "schema": {"type": "string", "format": "uuid"}}},
                "requestBodies": {"NewPet": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewPet"}}}}},
                "schemas": {
                  "Base": {"type": "object", "properties": {"id": {"type": "string", "readOnly": true}, "name": {"type": "string", "maxLength": 30}}},
                  "Pet": {"allOf": [{"$ref": "#/components/schemas/Base"}, {"type": "object", "properties": {"tag": {"type": ["string", "null"]}}}]},
                  "Kind": {"type": "string", "enum": ["DOG", "CAT"]},
                  "NewPet": {"type": "object", "required": ["name", "kind"],
                    "properties": {
                      "name": {"type": "string", "minLength": 1, "example": "Rex"},
                      "kind": {"$ref": "#/components/schemas/Kind"},
                      "weight": {"type": "number", "exclusiveMinimum": 0},
                      "owner": {"$ref": "#/components/schemas/NewPet"},
                      "toys": {"type": "array", "maxItems": 4, "items": {"type": "string"}},
                      "secretToken": {"type": "string"}
                    }}
                }
              }
            }
            """;

    private final List<String> log = new ArrayList<>();
    private final ApiCatalog catalog = new OpenApiReader(log::add).read(DOC);

    private ApiEndpoint op(String id) {
        return catalog.endpoints().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void readsOperationsPathLevelParametersAndBasePath() {
        assertThat(catalog.project()).isEqualTo("pets");
        assertThat(catalog.basePath()).isEqualTo("/petstore");
        assertThat(catalog.endpoints()).extracting(ApiEndpoint::id)
                .containsExactly("getPet", "updatePet", "getPets", "createPet", "uploadPhoto", "searchPets");
        assertThat(op("getPet").params()).singleElement().satisfies(p -> {
            assertThat(p.in()).isEqualTo(ParamLocation.PATH);
            assertThat(((ScalarSchema) p.schema()).format()).isEqualTo("uuid");
        });
        assertThat(op("getPet").tags()).containsExactly("pets");
    }

    @Test
    void dropsAuthorizationAndCookieParametersAndSkipsNonJsonBodies() {
        assertThat(op("getPets").params()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("limit");
            assertThat(p.defaultValue()).isEqualTo("20");
            assertThat(((ScalarSchema) p.schema()).constraints().maximum()).isEqualTo(new BigDecimal("100"));
        });
        assertThat(log).anyMatch(l -> l.contains("/pets/{petId}/raw") && l.contains("application/octet-stream"));
        assertThat(catalog.endpoints()).extracting(ApiEndpoint::id).doesNotContain("rawBytes");
    }

    @Test
    void readsMultipartAndFormBodiesWithBinaryFileFields() {
        assertThat(op("uploadPhoto").bodyType()).isEqualTo("multipart");
        ObjectSchema upload = (ObjectSchema) op("uploadPhoto").body();
        assertThat(((ScalarSchema) upload.properties().get("file").schema()).format()).isEqualTo("binary");
        assertThat(upload.properties().get("file").required()).isTrue();
        assertThat(op("searchPets").bodyType()).isEqualTo("form");
        assertThat(op("createPet").bodyType()).as("JSON stays the default").isNull();
    }

    @Test
    void resolvesRefsAllOfReadOnlyEnumsAndRecursion() {
        assertThat(op("createPet").body()).isEqualTo(new RefSchema("NewPet"));
        ObjectSchema newPet = catalog.schemas().get("NewPet");
        assertThat(newPet.properties().get("name").required()).isTrue();
        assertThat(((ScalarSchema) newPet.properties().get("name").schema()).example()).isEqualTo("Rex");
        assertThat(((ScalarSchema) newPet.properties().get("kind").schema()).enumValues()).containsExactly("DOG", "CAT");
        assertThat(((ScalarSchema) newPet.properties().get("weight").schema()).constraints().minimum())
                .isEqualByComparingTo("0.01");
        assertThat(newPet.properties().get("owner").schema()).isEqualTo(new RefSchema("NewPet"));
        assertThat(((ArraySchema) newPet.properties().get("toys").schema()).maxItems()).isEqualTo(4);
        assertThat(newPet.properties().get("secretToken").sensitive()).isTrue();

        ObjectSchema pet = catalog.schemas().get("Pet");
        assertThat(pet.properties().keySet()).containsExactly("name", "tag"); // id is readOnly
        assertThat(((ScalarSchema) pet.properties().get("tag").schema()).type()).isEqualTo(ScalarType.STRING);
    }

    @Test
    void readsYamlAndRejectsSwagger2() {
        ApiCatalog yaml = new OpenApiReader(s -> { }).read("""
                openapi: 3.0.3
                info: {title: y, version: '1'}
                paths:
                  /ping:
                    get: {operationId: ping}
                """);
        assertThat(yaml.endpoints()).extracting(ApiEndpoint::id).containsExactly("ping");
        assertThatThrownBy(() -> new OpenApiReader(s -> { }).read("{\"swagger\": \"2.0\", \"paths\": {}}"))
                .hasMessageContaining("Swagger 2");
    }
}
