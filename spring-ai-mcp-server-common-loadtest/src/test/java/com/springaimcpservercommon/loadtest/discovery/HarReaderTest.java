package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarReaderTest {

    private final List<String> log = new ArrayList<>();

    private HarCapture read(String basePath, List<String> known, List<String> hosts) {
        return new HarReader(log::add).read(Fixtures.sampleShopHar(), new HarReader.Options(hosts, basePath, known));
    }

    private static ApiEndpoint op(HarCapture c, HttpMethod method, String path) {
        return c.catalog().endpoints().stream().filter(e -> e.method() == method && e.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError(method + " " + path + " not in " + c.catalog().endpoints()));
    }

    @Test
    void keepsOnlyApiCallsOfTheMainHostAndTemplatesUrls() {
        HarCapture c = read("/shop", List.of(), List.of());
        assertThat(c.origin()).isEqualTo("https://shop.local:8443");
        assertThat(c.catalog().endpoints()).extracting(ApiEndpoint::displayName).containsExactlyInAnyOrder(
                "POST /api/v1/auth/login", "GET /api/v1/customers", "GET /api/v1/customers/{customerId}",
                "POST /api/v1/orders", "GET /api/v1/orders/{orderId}", "GET /api/v1/products",
                "GET /api/v1/products/{productId}", "PUT /api/v1/customers/{customerId}",
                "DELETE /api/v1/customers/{customerId}");
        assertThat(log).anyMatch(l -> l.contains("google-analytics") && l.contains("--har-host"))
                .anyMatch(l -> l.contains("/feedback") && l.contains("not JSON"))
                .anyMatch(l -> l.contains("4 non-API requests skipped")); // document, script, preflight, image
        assertThat(op(c, HttpMethod.GET, "/api/v1/orders/{orderId}").id()).isEqualTo("getOrdersById");
    }

    @Test
    void knownTemplatesFromTheProjectWinOverHeuristics() {
        HarCapture c = read("/shop", List.of("/api/v1/products/{sku}", "/api/v1/customers/{id}"), List.of());
        assertThat(op(c, HttpMethod.GET, "/api/v1/products/{sku}").params()).extracting(ApiParam::name)
                .contains("sku");
        assertThat(op(c, HttpMethod.PUT, "/api/v1/customers/{id}")).isNotNull();
    }

    @Test
    void observationsAreSuccessfulCallsInOrderWithDecodedResponses() {
        HarCapture c = read("/shop", List.of(), List.of());
        assertThat(c.observations()).extracting(o -> o.method() + " " + o.template()).containsExactly(
                "POST /api/v1/auth/login", "GET /api/v1/customers", "GET /api/v1/customers/{customerId}",
                "POST /api/v1/orders", "GET /api/v1/orders/{orderId}", "GET /api/v1/products",
                "GET /api/v1/products/{productId}", "GET /api/v1/products/{productId}",
                "PUT /api/v1/customers/{customerId}", "DELETE /api/v1/customers/{customerId}"); // the 404 is not replayed
        HarCapture.Observation list = c.observations().get(1);
        assertThat(list.query()).containsEntry("page", List.of("0")).containsEntry("size", List.of("20"));
        assertThat(list.response().path("content").path(0).path("id").asInt()).isEqualTo(41); // base64 content
        assertThat(c.observations().get(4).pathValues()).containsExactly("9001");
    }

    @Test
    void neverKeepsCredentialHeaders() {
        HarCapture c = read("/shop", List.of(), List.of());
        for (HarCapture.Observation o : c.observations()) {
            assertThat(o.headers().keySet()).allMatch(h -> h.equals("X-Tenant-Id") || h.equals("X-Request-Id"));
        }
        assertThat(op(c, HttpMethod.PUT, "/api/v1/customers/{customerId}").params(ParamLocation.HEADER))
                .extracting(ApiParam::name).containsExactlyInAnyOrder("X-Tenant-Id", "X-Request-Id");
    }

    @Test
    void infersParameterAndBodySchemasFromRecordedValues() {
        HarCapture c = read("/shop", List.of(), List.of());
        ApiParam id = op(c, HttpMethod.GET, "/api/v1/customers/{customerId}").params(ParamLocation.PATH).getFirst();
        assertThat(((ScalarSchema) id.schema()).type()).isEqualTo(ScalarType.INTEGER);
        assertThat(op(c, HttpMethod.GET, "/api/v1/customers").params(ParamLocation.QUERY))
                .allMatch(ApiParam::required);

        ObjectSchema order = (ObjectSchema) op(c, HttpMethod.POST, "/api/v1/orders").body();
        assertThat(order.properties().keySet()).containsExactly("customerId", "lines", "couponCode", "deliveryNotes");
        ObjectSchema line = (ObjectSchema) ((ArraySchema) order.properties().get("lines").schema()).items();
        assertThat(((ScalarSchema) line.properties().get("quantity").schema()).type()).isEqualTo(ScalarType.INTEGER);

        ObjectSchema customer = (ObjectSchema) op(c, HttpMethod.PUT, "/api/v1/customers/{customerId}").body();
        assertThat(customer.properties().get("password").sensitive()).isTrue();
        assertThat(((ScalarSchema) customer.properties().get("email").schema()).format()).isEqualTo("email");
    }

    @Test
    void hostsCanBeChosenAndNonHarInputIsRejected() {
        HarCapture analytics = read(null, List.of(), List.of("www.google-analytics.com"));
        assertThat(analytics.catalog().endpoints()).extracting(ApiEndpoint::path).containsExactly("/g/collect");
        assertThatThrownBy(() -> new HarReader(s -> { }).read("{\"openapi\":\"3.0.0\"}",
                new HarReader.Options(List.of(), null, List.of()))).hasMessageContaining("not a HAR");
    }

    @Test
    void operationIdsIgnoreContextPathAndVersion() {
        assertThat(HarReader.operationId(HttpMethod.GET, "/shop/api/v2/order-lines/{id}")).isEqualTo("getOrderLinesById");
        assertThat(HarReader.operationId(HttpMethod.POST, "/graphql")).isEqualTo("postGraphql");
    }
}
