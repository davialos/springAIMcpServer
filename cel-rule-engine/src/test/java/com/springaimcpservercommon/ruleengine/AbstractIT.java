package com.springaimcpservercommon.ruleengine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** The application on a random port against a PostgreSQL container shared by every integration test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    JsonNode send(String method, String path, String json, String tenant) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (tenant != null) {
            req.header("X-Tenant-Code", tenant);
        }
        req.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
        if (body.isObject()) {
            ((tools.jackson.databind.node.ObjectNode) body).put("_http", res.statusCode());
        }
        return body;
    }

    JsonNode post(String path, String json) throws IOException, InterruptedException {
        return send("POST", path, json, "ACME");
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, "ACME");
    }
}
