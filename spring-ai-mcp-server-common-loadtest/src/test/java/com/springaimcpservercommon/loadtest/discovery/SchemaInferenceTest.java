package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInferenceTest {

    private static Schema infer(String json) {
        return SchemaInference.infer(Documents.parse(json));
    }

    @Test
    void propertiesAreRequiredOnlyWhenEverySampleHasThem() {
        ObjectSchema merged = (ObjectSchema) SchemaInference.merge(infer("{\"a\": 1, \"b\": \"x\"}"),
                infer("{\"a\": 2.5, \"c\": null}"));
        assertThat(merged.properties().get("a").required()).isTrue();
        assertThat(((ScalarSchema) merged.properties().get("a").schema()).type()).isEqualTo(ScalarType.NUMBER);
        assertThat(merged.properties().get("b").required()).isFalse();
        assertThat(merged.properties().get("c").required()).isFalse();
    }

    @Test
    void detectsStringFormatsAndTextTypes() {
        assertThat(((ScalarSchema) infer("\"2026-09-30\"")).format()).isEqualTo("date");
        assertThat(((ScalarSchema) infer("\"2026-09-30T10:00:00Z\"")).format()).isEqualTo("date-time");
        assertThat(((ScalarSchema) infer("\"a@b.io\"")).format()).isEqualTo("email");
        assertThat(((ScalarSchema) infer("\"1b4e28ba-2fa1-11d2-883f-0016d3cca427\"")).format()).isEqualTo("uuid");
        assertThat(SchemaInference.inferText("42").type()).isEqualTo(ScalarType.INTEGER);
        assertThat(SchemaInference.inferText("true").type()).isEqualTo(ScalarType.BOOLEAN);
        assertThat(SchemaInference.merge(SchemaInference.inferText("42"), SchemaInference.inferText("abc")))
                .isEqualTo(ScalarSchema.of(ScalarType.STRING, null));
        assertThat(infer("null")).isNull();
    }
}
