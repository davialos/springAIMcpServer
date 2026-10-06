package com.springaimcpservercommon.loadtest.runtime;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.model.Access;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract between this module and the generator: the document {@link RuntimeModelBuilder} writes from a real Spring
 * MVC context is what {@code loadtest generate --runtime} reads, over HTTP from {@code /actuator/loadtest} as well as
 * from a saved file.
 */
class GeneratorContractTest {

    @TempDir
    static Path dir;
    private static AnnotationConfigWebApplicationContext context;
    private static String json;
    private static HttpServer server;

    @BeforeAll
    static void start() throws IOException {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RuntimeModelBuilderTest.Config.class);
        context.refresh();
        json = JsonMapper.builder().build().writeValueAsString(new RuntimeModelBuilder(
                new LoadTestRuntimeProperties(true, false, null, null)).build("shop", "/shop",
                List.of(context.getBean(RequestMappingHandlerMapping.class))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/actuator/loadtest", ex -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        context.close();
    }

    private static ApiEndpoint endpoint(LoadTestGenerator.DiscoveryResult r, String id) {
        return r.catalog().endpoints().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(
                () -> new AssertionError(id + " in " + r.catalog().endpoints().stream().map(ApiEndpoint::id).toList()));
    }

    @Test
    void theGeneratorReadsTheRuntimeModelOverHttp() {
        LoadTestGenerator.DiscoveryResult r = LoadTestGenerator.builder()
                .runtime("http://127.0.0.1:" + server.getAddress().getPort() + "/shop").noDatabase().build().discover();
        assertThat(r.basePath()).isEqualTo("/shop");
        assertThat(r.catalog().endpoints()).extracting(ApiEndpoint::id).contains("create", "get", "search", "update",
                "delete", "upload", "open");
        ApiEndpoint create = endpoint(r, "create");
        assertThat(create.sources()).containsExactly("runtime");
        ObjectSchema body = (ObjectSchema) create.body();
        assertThat(body.properties()).containsKeys("product", "quantity", "currency", "ref").doesNotContainKey("internal");
        assertThat(body.properties().get("product").required()).isTrue();
        assertThat(endpoint(r, "update").access()).isEqualTo(Access.roles(List.of("ADMIN")));
        assertThat(endpoint(r, "open").access()).isEqualTo(Access.open());
        assertThat(endpoint(r, "create").access()).isNull();
        assertThat(endpoint(r, "search").params()).anyMatch(p -> p.name().equals("status") && p.in() == ParamLocation.QUERY)
                .anyMatch(p -> p.name().equals("X-Tenant") && p.in() == ParamLocation.HEADER);
        assertThat(endpoint(r, "get").responseSchema().path("properties").has("quantity")).isTrue();
        assertThat(endpoint(r, "upload").bodyType()).isEqualTo("multipart");
    }

    @Test
    void aSavedCopyAndTheFullEndpointUrlWorkToo() throws IOException {
        Path file = Files.writeString(dir.resolve("runtime.json"), json);
        assertThat(LoadTestGenerator.builder().runtime(file.toString()).noDatabase().build().discover().catalog().endpoints())
                .isNotEmpty();
        assertThat(LoadTestGenerator.builder().runtime("http://127.0.0.1:" + server.getAddress().getPort()
                + "/shop/actuator/loadtest/").noDatabase().build().discover().catalog().endpoints()).isNotEmpty();
    }

    @Test
    void anUnreachableModelSaysWhatTheApplicationNeeds() {
        assertThatThrownBy(() -> LoadTestGenerator.builder().runtime("http://127.0.0.1:1/shop").noDatabase().build().discover())
                .hasMessageContaining("loadtest.runtime.enabled=true").hasMessageContaining("exposure.include=loadtest");
    }

    @Test
    void theSuiteIsGeneratedFromTheRuntimeModelAlone() {
        Path suite = dir.resolve("suite");
        LoadTestGenerator.builder().runtime("http://127.0.0.1:" + server.getAddress().getPort() + "/shop").outDir(suite)
                .noDatabase().build().generate();
        assertThat(suite.resolve("main.js")).exists();
        assertThat(suite.resolve("loadtest.config.json")).content().contains("\"baseUrl\" : \"http://localhost:8080/shop\"")
                .contains("\"auth\" : \"ADMIN\"");
    }
}
