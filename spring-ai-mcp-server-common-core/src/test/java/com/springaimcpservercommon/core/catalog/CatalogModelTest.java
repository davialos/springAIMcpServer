package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.lint.SecretScanner;
import com.springaimcpservercommon.core.lint.SensitiveNames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogModelTest {

    @Test
    void toolNamesDefaultToSnakeCase() {
        assertThat(ToolNames.snakeCase("findRecentOrders")).isEqualTo("find_recent_orders");
        assertThat(ToolNames.snakeCase("getURLList")).isEqualTo("get_url_list");
        assertThat(ToolNames.snakeCase("top10Orders")).isEqualTo("top10_orders");
        assertThat(ToolNames.isValid("find_orders")).isTrue();
        assertThat(ToolNames.isValid("go")).isFalse();
        assertThat(ToolNames.isValid("Find")).isFalse();
        assertThat(ToolNames.isValid("a".repeat(65))).isFalse();
    }

    @Test
    void canonicalJsonSortsKeysAndEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", List.of(1, 2.5, new BigDecimal("10.00")));
        m.put("a", "q\"\n");
        assertThat(CanonicalJson.write(m)).isEqualTo("{\"a\":\"q\\\"\\n\",\"b\":[1,2.5,10]}");
        assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonSchemaEqualityUsesCanonicalText() {
        JsonSchema a = JsonSchema.of(Map.of("type", "object", "properties", Map.of()));
        assertThat(a).isEqualTo(JsonSchema.emptyObject());
        assertThat(a.json()).isEqualTo("{\"properties\":{},\"type\":\"object\"}");
        assertThatThrownBy(() -> a.tree().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fingerprintIsOrderIndependentAndContentSensitive() {
        Instant t = Instant.parse("2026-09-28T10:00:00Z");
        ContextDescriptor c1 = ctx("com.a.One", "One");
        ContextDescriptor c2 = ctx("com.a.Two", "Two");
        ScannedCatalog x = ScannedCatalog.of(t, null, List.of(), List.of(), List.of(c1, c2), List.of());
        ScannedCatalog y = ScannedCatalog.of(t.plusSeconds(5), "2", List.of(), List.of(), List.of(c2, c1), List.of());
        assertThat(x.scanFingerprint()).isEqualTo(y.scanFingerprint()).startsWith("sha256:");
        assertThat(List.copyOf(y.contexts().keySet())).containsExactly(c1.ref(), c2.ref());
        ScannedCatalog z = ScannedCatalog.of(t, null, List.of(), List.of(), List.of(c1, ctx("com.a.Two", "Changed")), List.of());
        assertThat(z.scanFingerprint()).isNotEqualTo(x.scanFingerprint());
        assertThatThrownBy(() -> ScannedCatalog.of(t, null, List.of(), List.of(), List.of(c1, c1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryIgnoresOlderGenerations() {
        EffectiveCatalog g1 = new EffectiveCatalog(1, "s", "p", Map.of(), Map.of(), List.of(), List.of());
        EffectiveCatalog g2 = new EffectiveCatalog(2, "s", "p", Map.of(), Map.of(), List.of(), List.of());
        SwappableMetadataRegistry registry = new SwappableMetadataRegistry(g1);
        assertThat(registry.publish(g2)).isTrue();
        assertThat(registry.publish(g1)).isFalse();
        assertThat(registry.current()).isSameAs(g2);
    }

    @Test
    void sensitiveNamesMatchTokensNotSubstrings() {
        SensitiveNames names = SensitiveNames.defaults();
        assertThat(names.looksSensitive("userPassword")).isTrue();
        assertThat(names.looksSensitive("api_key")).isTrue();
        assertThat(names.looksSensitive("pinCode")).isTrue();
        assertThat(names.looksSensitive("shippingAddress")).isFalse();
        assertThat(names.looksSensitive("lessons")).isFalse();
        SensitiveNames confirmed = new SensitiveNames(SensitiveNames.DEFAULT_TERMS, Set.of("com.a.B#tokenCount"));
        assertThat(confirmed.check("com.a.B", "tokenCount")).isEqualTo(SensitiveNames.Verdict.CONFIRMED);
        assertThat(confirmed.check("com.a.C", "tokenCount")).isEqualTo(SensitiveNames.Verdict.UNCONFIRMED);
    }

    @Test
    void secretScannerFindsTokensButNotProse() {
        SecretScanner scanner = new SecretScanner();
        assertThat(scanner.findSecret("key AKIAABCDEFGHIJKLMNOP here")).contains("aws-access-key-id");
        assertThat(scanner.findSecret("password: s3cr3tValue")).contains("credential-assignment");
        assertThat(scanner.findSecret("Returns the date of the last password reset")).isEmpty();
    }

    private static ContextDescriptor ctx(String type, String description) {
        return new ContextDescriptor(new CatalogElementRef(CatalogElementRef.Kind.CTX, type), "bean", type, null,
                "name", description, List.of(), Classification.INTERNAL, false);
    }
}
