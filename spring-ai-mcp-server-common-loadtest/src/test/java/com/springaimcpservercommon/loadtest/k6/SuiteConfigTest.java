package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuiteConfigTest {

    private static final ApiCatalog CATALOG =
            CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop())));
    private static final SuiteConfig.Settings SETTINGS =
            new SuiteConfig.Settings("http://localhost:8081/shop", "auto", "none", null);

    @Test
    void defaultsWeighReadsAndDisableDeletes() {
        ObjectNode c = SuiteConfig.defaults(CATALOG, SETTINGS);
        assertThat(c.path("apis").path("getCustomer").path("weight").asInt()).isEqualTo(6);
        assertThat(c.path("apis").path("createOrder").path("weight").asInt()).isEqualTo(2);
        assertThat(c.path("apis").path("deleteCustomer").path("enabled").asBoolean()).isFalse();
        assertThat(c.path("modes").propertyNames())
                .containsExactly("smoke", "load", "stress", "spike", "soak", "breakpoint");
        assertThat(c.path("modes").path("breakpoint").path("abortOnFail").asBoolean()).isTrue();
        assertThat(c.toString()).doesNotContain("AUTH_TOKEN\":\"").doesNotContainIgnoringCase("password\":\"p");
    }

    @Test
    void regenerationKeepsEditsAddsNewApisAndDropsRemovedOnes() {
        ObjectNode old = SuiteConfig.defaults(CATALOG, SETTINGS);
        old.put("baseUrl", "https://staging.example.com");
        ((ObjectNode) old.path("apis").path("getCustomer")).put("weight", 42).put("p95Ms", 250);
        ((ObjectNode) old.path("modes").path("spike")).put("baseVus", 99);
        ((ObjectNode) old.path("apis")).putObject("goneApi").put("weight", 1);
        ((ObjectNode) old.path("apis")).remove("listProducts");

        List<ApiEndpoint> fewer = CATALOG.endpoints();
        ObjectNode merged = SuiteConfig.merge(SuiteConfig.defaults(CATALOG, SETTINGS), old);

        assertThat(merged.path("baseUrl").asString()).isEqualTo("https://staging.example.com");
        assertThat(merged.path("apis").path("getCustomer").path("weight").asInt()).isEqualTo(42);
        assertThat(merged.path("apis").path("getCustomer").path("p95Ms").asInt()).isEqualTo(250);
        assertThat(merged.path("modes").path("spike").path("baseVus").asInt()).isEqualTo(99);
        assertThat(merged.path("modes").path("spike").path("stages").isArray()).isTrue();
        assertThat(merged.path("apis").has("goneApi")).isFalse();
        assertThat(merged.path("apis").has("listProducts")).isTrue();
        assertThat(merged.path("apis").size()).isEqualTo(fewer.size());
    }

    @Test
    void everyModeHasAMixedFormAndPreview() {
        assertThat(LoadMode.modeNames()).contains("smoke", "spike", "stress", "mixed-spike", "mixed-stress",
                "mixed-load", "breakpoint", "preview");
    }
}
