package com.springaimcpservercommon.core.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct tests for the single canonical-JSON writer (ADR-0020). {@code persistence.support.CanonicalJson} and
 * {@code persistence.config.CanonicalSpec} delegate here; their own tests additionally prove that a value they
 * parse externally renders identically to a value built directly against this class.
 */
class CanonicalJsonTest {

    @Test
    void sortsObjectKeys() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 2);
        map.put("a", 1);
        assertThat(CanonicalJson.write(map)).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void writesUuidAsItsStringForm() {
        UUID id = UUID.fromString("0190a3b4-0000-7000-8000-000000000001");
        assertThat(CanonicalJson.write(id)).isEqualTo("\"" + id + "\"");
    }

    @Test
    void normalisesBigDecimal() {
        assertThat(CanonicalJson.write(new BigDecimal("1.500"))).isEqualTo("1.5");
        assertThat(CanonicalJson.write(new BigDecimal("-0.0"))).isEqualTo("0");
        assertThat(CanonicalJson.write(new BigDecimal("100"))).isEqualTo("100");
    }

    @Test
    void escapesLineAndParagraphSeparators() {
        // U+2028/U+2029 are valid inside a JSON string but are line terminators in some JS/log contexts;
        // this writer always escapes them so canonical output is safe to embed anywhere (ADR-0020).
        assertThat(CanonicalJson.write("a b c")).isEqualTo("\"a\\u2028b\\u2029c\"");
    }

    @Test
    void rejectsNestingDeeperThanMaxDepth() {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i <= CanonicalJson.MAX_DEPTH; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("n", next);
            cursor = next;
        }
        assertThatThrownBy(() -> CanonicalJson.write(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immutableCopyIsUnmodifiableAndDepthGuarded() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", List.of(1, 2));
        Object copy = CanonicalJson.immutableCopy(map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> copyMap = (Map<Object, Object>) copy;
        assertThat(copy).isInstanceOf(Map.class);
        assertThatThrownBy(() -> copyMap.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i <= CanonicalJson.MAX_DEPTH; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("n", next);
            cursor = next;
        }
        assertThatThrownBy(() -> CanonicalJson.immutableCopy(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> CanonicalJson.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteFloatingPoint() {
        assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }
}
