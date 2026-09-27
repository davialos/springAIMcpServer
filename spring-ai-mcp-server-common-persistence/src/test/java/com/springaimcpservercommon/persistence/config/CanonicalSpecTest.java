package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.hash.Sha256;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalSpecTest {

    @Test
    void sortsKeysRecursivelyAndDropsWhitespace() {
        CanonicalSpec spec = CanonicalSpec.of("""
                { "b" : 1, "a" : { "z": true, "y": [3, 1, {"k": null, "c": "x"}] } }
                """);

        assertThat(spec.json()).isEqualTo("{\"a\":{\"y\":[3,1,{\"c\":\"x\",\"k\":null}],\"z\":true},\"b\":1}");
        assertThat(spec.hash()).isEqualTo(Sha256.of(spec.json()));
        assertThat(Sha256.isValid(spec.hash())).isTrue();
    }

    @Test
    void keyOrderAndFormattingDoNotChangeTheHash() {
        assertThat(CanonicalSpec.of("{\"x\":1,\"y\":\"v\"}").hash())
                .isEqualTo(CanonicalSpec.of("{\n  \"y\": \"v\",\n  \"x\": 1\n}").hash());
    }

    @Test
    void arrayOrderIsSignificant() {
        assertThat(CanonicalSpec.of("{\"a\":[1,2]}").hash()).isNotEqualTo(CanonicalSpec.of("{\"a\":[2,1]}").hash());
    }

    @Test
    void normalisesNumbersToPlainDecimals() {
        assertThat(CanonicalSpec.of("{\"a\":1.0,\"b\":1e2,\"c\":-0.0,\"d\":0.50,\"e\":12345678901234567890}").json())
                .isEqualTo("{\"a\":1,\"b\":100,\"c\":0,\"d\":0.5,\"e\":12345678901234567890}");
        assertThat(CanonicalSpec.of("{\"a\":1}").hash()).isEqualTo(CanonicalSpec.of("{\"a\":1.000}").hash());
    }

    @Test
    void survivesPostgresJsonbRenderingRoundTrip() {
        // jsonb output: keys ordered by length then bytes, ", " and ": " separators, numeric scale kept
        String original = "{\"limit\":10.50,\"name\":\"orders\",\"id\":7}";
        String jsonbRendering = "{\"id\": 7, \"name\": \"orders\", \"limit\": 10.50}";

        assertThat(CanonicalSpec.of(jsonbRendering)).isEqualTo(CanonicalSpec.of(original));
    }

    @Test
    void isIdempotent() {
        CanonicalSpec once = CanonicalSpec.of("{\"b\":[{\"d\":2,\"c\":1}],\"a\":\"é\\u0001\\n\\\"\"}");

        assertThat(CanonicalSpec.of(once.json())).isEqualTo(once);
    }

    @Test
    void escapesOnlyQuotesBackslashesAndControlCharacters() {
        assertThat(CanonicalSpec.of("{\"s\":\"a\\\"b\\\\c\\u0001\\t\\u00e9/\"}").json())
                .isEqualTo("{\"s\":\"a\\\"b\\\\c\\u0001\\té/\"}");
    }

    @Test
    void rejectsNonObjectsAndInvalidJson() {
        assertThatThrownBy(() -> CanonicalSpec.of("[1,2]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalSpec.of("\"x\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalSpec.of("{\"a\":")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalSpec.of("{} {}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHugeNumbers() {
        assertThatThrownBy(() -> CanonicalSpec.of("{\"a\":1e999999}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsMismatchedHash() {
        assertThatThrownBy(() -> new CanonicalSpec("{}", Sha256.of("{ }")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
