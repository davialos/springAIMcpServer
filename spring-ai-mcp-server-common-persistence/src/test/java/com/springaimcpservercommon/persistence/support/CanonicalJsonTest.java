package com.springaimcpservercommon.persistence.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    @Test
    void sortsKeysAndRemovesWhitespace() {
        assertThat(CanonicalJson.canonicalize("{ \"b\" : [1, 2 ,{\"z\":null,\"a\":true}], \"a\":\"x\" }"))
                .isEqualTo("{\"a\":\"x\",\"b\":[1,2,{\"a\":true,\"z\":null}]}");
    }

    @Test
    void jsonbStyleOutputCanonicalisesLikeTheInput() {
        // PostgreSQL returns jsonb with its own key order (shorter keys first) and spaces
        String inserted = "{\"longer\":1.50,\"k\":\"v\",\"nested\":{\"b\":2,\"a\":1e2}}";
        String returnedByJsonb = "{\"k\": \"v\", \"longer\": 1.50, \"nested\": {\"a\": 100, \"b\": 2}}";

        assertThat(CanonicalJson.canonicalize(returnedByJsonb)).isEqualTo(CanonicalJson.canonicalize(inserted));
    }

    @Test
    void normalisesNumbers() {
        assertThat(CanonicalJson.canonicalize("[1.50, 15e-1, -0, 0.0, 100, 1E+2, -12.3400]"))
                .isEqualTo("[1.5,1.5,0,0,100,100,-12.34]");
    }

    @Test
    void escapesStringsMinimally() {
        assertThat(CanonicalJson.canonicalize("\"a\\\"b\\\\c\\u0001\\n\\u00e9\\/\""))
                .isEqualTo("\"a\\\"b\\\\c\\u0001\\n\u00e9/\"");
    }

    @Test
    void lastDuplicateKeyWinsLikeJsonb() {
        assertThat(CanonicalJson.canonicalize("{\"a\":1,\"a\":2}")).isEqualTo("{\"a\":2}");
    }

    @Test
    void rejectsInvalidJson() {
        for (String bad : List.of("", "{", "{\"a\":1,}", "[1,]", "01", "1.", "tru", "\"\\x\"", "{a:1}", "1 2",
                "\"\\ud800\"", "1e99999")) {
            assertThatThrownBy(() -> CanonicalJson.canonicalize(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void canonicalizeObjectRequiresAnObject() {
        assertThat(CanonicalJson.canonicalizeObject("{}")).isEqualTo("{}");
        assertThatThrownBy(() -> CanonicalJson.canonicalizeObject("[]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesJavaValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        UUID id = UUID.fromString("0190a3b4-0000-7000-8000-000000000001");
        map.put("z", List.of(1L, new BigDecimal("2.500"), 3.25d));
        map.put("id", id);
        map.put("n", null);
        map.put("e", Thread.State.NEW);

        assertThat(CanonicalJson.write(map))
                .isEqualTo("{\"e\":\"NEW\",\"id\":\"" + id + "\",\"n\":null,\"z\":[1,2.5,3.25]}");
        assertThat(CanonicalJson.write(Arrays.asList("a", null))).isEqualTo("[\"a\",null]");
        assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
