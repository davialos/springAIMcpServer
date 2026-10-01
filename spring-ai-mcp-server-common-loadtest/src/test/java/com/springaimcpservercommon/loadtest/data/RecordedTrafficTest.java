package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.HarCapture;
import com.springaimcpservercommon.loadtest.discovery.HarReader;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordedTrafficTest {

    private static RecordedTraffic recorded;

    @BeforeAll
    static void mergeRecordingWithProject() {
        ApiCatalog source = new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop());
        List<String> known = new ArrayList<>();
        source.endpoints().forEach(e -> known.add(e.path()));
        HarCapture har = new HarReader(s -> { }).read(Fixtures.sampleShopHar(),
                new HarReader.Options(List.of(), "/shop", known));
        ApiCatalog catalog = CatalogMerger.filter(CatalogMerger.merge(List.of(source, har.catalog())), List.of(),
                CatalogMerger.DEFAULT_EXCLUDES);
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(new TableIndex(catalog.entities(), List.of()), Map.of()));
        assertThat(catalog.endpoints()).extracting(ApiEndpoint::id).contains("getCustomer", "createOrder", "postAuthLogin");
        recorded = new RecordedTraffic(List.of(har), catalog, plan);
    }

    @Test
    void recordedValuesLandUnderTheSameKeysAsTheDataPlan() {
        UserData u = recorded.userData();
        assertThat(u.fields().get("getCustomer.path.id")).containsExactly(41L);
        assertThat(u.fields().get("getProduct.path.sku")).containsExactly("SKU-7", "SKU-12");
        assertThat(u.fields().get("CreateOrderRequest.customerId")).containsExactly(41L);
        assertThat(u.fields().get("OrderLine.productSku")).containsExactly("SKU-7");
        assertThat(u.fields().get("OrderLine.quantity")).containsExactly(2L);
        assertThat(u.fields().get("updateCustomer.header.X-Request-Id")).containsExactly("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThat(u.fields().get("listCustomers.query.size")).containsExactly(20L);
        assertThat(u.fields().get("Address.city")).containsExactly("Paris");
        assertThat(u.fields().get("postAuthLogin.body.username")).containsExactly("alice");
    }

    @Test
    void secretsAreNeverKept() {
        UserData u = recorded.userData();
        assertThat(u.fields().keySet()).noneMatch(k -> k.toLowerCase().contains("password"));
        assertThat(u.toJson().toString()).doesNotContain("S3cret!pw").doesNotContain("N3wPassw0rd!")
                .doesNotContain("eyJ").doesNotContain("csrf-123").doesNotContain("SESSION");
        assertThat(u.payloads()).containsOnlyKeys("createOrder"); // login and update carried a password
        assertThat(recorded.journey().toString()).doesNotContain("S3cret!pw").doesNotContain("N3wPassw0rd!")
                .doesNotContain("eyJ").doesNotContain("csrf-123");
    }

    @Test
    void journeyKeepsOrderPausesAndCorrelatesReturnedIds() {
        ArrayNode steps = recorded.journey();
        List<String> apis = new ArrayList<>();
        steps.forEach(s -> apis.add(s.path("api").asString()));
        assertThat(apis).containsExactly("postAuthLogin", "listCustomers", "getCustomer", "createOrder", "getOrder",
                "listProducts", "getProduct", "getProduct", "updateCustomer", "deleteCustomer");
        assertThat(steps.get(0).path("fill").toString()).isEqualTo("[\"password\"]");
        assertThat(steps.get(8).path("fill").toString()).isEqualTo("[\"password\"]");
        assertThat(steps.get(1).path("pauseMs").asLong()).isEqualTo(1400);

        JsonNode customer = steps.get(2).path("path").path("id");
        assertThat(customer.path("$from").asInt()).isEqualTo(1);
        assertThat(customer.path("at").asString()).isEqualTo("content.0.id");
        assertThat(customer.path("recorded").asInt()).isEqualTo(41);

        JsonNode order = steps.get(4).path("path").path("orderId");
        assertThat(order.path("$from").asInt()).isEqualTo(3); // the id createOrder returned
        assertThat(order.path("at").asString()).isEqualTo("id");

        JsonNode sku = steps.get(6).path("path").path("sku");
        assertThat(sku.path("$from").asInt()).isEqualTo(5); // returned by the product search
        assertThat(steps.get(3).path("body").path("lines").path(0).path("quantity").asInt()).isEqualTo(2); // not an id

        // 41 was last returned by getOrder (customerId); earlier responses are fallbacks, latest first
        JsonNode update = steps.get(8).path("path").path("id");
        assertThat(update.path("$from").asInt()).isEqualTo(4);
        assertThat(update.path("at").asString()).isEqualTo("customerId");
        assertThat(update.path("alt").path(0).path("$from").asInt()).isEqualTo(2);
        assertThat(update.path("alt").path(1).path("at").asString()).isEqualTo("content.0.id");
    }

    @Test
    void idLikeKeys() {
        assertThat(RecordedTraffic.idLike("id")).isTrue();
        assertThat(RecordedTraffic.idLike("customerId")).isTrue();
        assertThat(RecordedTraffic.idLike("order_uuid")).isTrue();
        assertThat(RecordedTraffic.idLike("sku")).isTrue();
        assertThat(RecordedTraffic.idLike("quantity")).isFalse();
        assertThat(RecordedTraffic.idLike("phoneNumber")).isFalse();
        assertThat(RecordedTraffic.idLike("apiKey")).isFalse();
    }
}
