package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMergerTest {

    private static final String MAPPINGS = """
            {"contexts": {"application": {"mappings": {"dispatcherServlets": {"dispatcherServlet": [
              {"handler": "x", "details": {"handlerMethod": {"className": "com.example.shop.web.CustomerController", "name": "get"},
                "requestMappingConditions": {"methods": ["GET"], "patterns": ["/api/v1/customers/{customerId}"]}}},
              {"handler": "x", "details": {"handlerMethod": {"className": "com.example.shop.web.CustomerController", "name": "create"},
                "requestMappingConditions": {"methods": ["POST"], "patterns": ["/api/v1/customers"]}}},
              {"handler": "dyn", "details": {"handlerMethod": {"className": "com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler", "name": "handle"},
                "requestMappingConditions": {"methods": ["GET"], "patterns": ["/dynamic-ai/api/sales/v1/orders/{customerId}"]}}},
              {"handler": "a", "details": {"handlerMethod": {"className": "org.springframework.boot.actuate.Health", "name": "health"},
                "requestMappingConditions": {"methods": ["GET"], "patterns": ["/actuator/health"]}}},
              {"handler": "ResourceHttpRequestHandler", "predicate": "/**"}
            ]}}}}}
            """;

    @Test
    void actuatorRoutesMergeWithSourceSchemasAndRuntimeOnlyRoutesAreKept() {
        ApiCatalog source = new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop());
        ApiCatalog actuator = new ActuatorMappingsReader(s -> { }).read(MAPPINGS);
        ApiCatalog merged = CatalogMerger.filter(CatalogMerger.merge(List.of(source, actuator)), List.of(),
                CatalogMerger.DEFAULT_EXCLUDES);

        ApiEndpoint create = merged.endpoints().stream()
                .filter(e -> e.method() == HttpMethod.POST && e.path().equals("/api/v1/customers")).findFirst().orElseThrow();
        assertThat(create.body()).isEqualTo(new RefSchema("CreateCustomerRequest")); // source schema beats actuator's unknown body
        assertThat(create.sources()).containsExactlyInAnyOrder("source", "actuator");

        ApiEndpoint get = merged.endpoints().stream()
                .filter(e -> e.method() == HttpMethod.GET && e.path().equals("/api/v1/customers/{id}")).findFirst().orElseThrow();
        assertThat(get.params()).hasSize(1); // {id} and {customerId} are the same path variable

        assertThat(merged.endpoints()).anyMatch(e -> e.path().startsWith("/dynamic-ai/api/")); // dynamic endpoints (LLD-04)
        assertThat(merged.endpoints()).noneMatch(e -> e.path().startsWith("/actuator"));
        assertThat(merged.endpoints()).extracting(ApiEndpoint::id).doesNotHaveDuplicates();
    }

    @Test
    void filtersByMethodPathPatternOrId() {
        ApiCatalog source = new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop());
        assertThat(CatalogMerger.filter(source, List.of("/api/v1/orders/**"), List.of()).endpoints())
                .extracting(ApiEndpoint::path).allMatch(p -> p.startsWith("/api/v1/orders"));
        assertThat(CatalogMerger.filter(source, List.of(), List.of("DELETE /**", "getProduct")).endpoints())
                .noneMatch(e -> e.method() == HttpMethod.DELETE || e.id().equals("getProduct"));
        assertThat(CatalogMerger.filter(source, List.of("GET /api/v1/customers/*"), List.of()).endpoints())
                .extracting(ApiEndpoint::displayName).containsExactly("GET /api/v1/customers/{id}");
    }
}
