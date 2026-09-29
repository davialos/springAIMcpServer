package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.OutputSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredOutputValidationAdvisorTest {

    private static final String SAMPLE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";

    // ── constructor guards ────────────────────────────────────────────────────

    @Test
    void constructor_wrongMode_throws() {
        OutputSpec spec = new OutputSpec(OutputSpec.Mode.TEXT, null);
        assertThatThrownBy(() -> new StructuredOutputValidationAdvisor(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON_SCHEMA");
    }

    @Test
    void getOrder_isAboveUsageMetering() {
        OutputSpec spec = new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, null);
        var advisor = new StructuredOutputValidationAdvisor(spec);
        assertThat(advisor.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100);
    }

    // ── Level 1: well-formedness ──────────────────────────────────────────────

    @Test
    void level1_validJsonObject_passes() {
        var advisor = advisorWithSchema(null, null);
        var response = callWith(advisor, "{\"name\":\"Alice\"}");
        assertThat(firstText(response)).isEqualTo("{\"name\":\"Alice\"}");
    }

    @Test
    void level1_invalidJson_blocksResponse() {
        var advisor = advisorWithSchema(null, null);
        var response = callWith(advisor, "not json at all");
        assertThat(firstText(response)).contains("output_invalid_json");
    }

    @Test
    void level1_jsonPrimitive_blocksResponse() {
        var advisor = advisorWithSchema(null, null);
        var response = callWith(advisor, "42");
        assertThat(firstText(response)).contains("output_not_json_object");
    }

    @Test
    void level1_blankOutput_blocksResponse() {
        var advisor = advisorWithSchema(null, null);
        var response = callWith(advisor, "");
        assertThat(firstText(response)).contains("output_empty");
    }

    // ── Level 2: schema conformance ───────────────────────────────────────────

    @Test
    void level2_noValidator_skipsSchemaCheck() {
        // Schema is present, but no validator → only well-formedness enforced
        var advisor = advisorWithSchema(SAMPLE_SCHEMA, null);
        // Missing required "name" field — but no validator, so it should pass
        var response = callWith(advisor, "{\"age\":30}");
        assertThat(firstText(response)).isEqualTo("{\"age\":30}");
    }

    @Test
    void level2_validatorPassesWhenNoErrors() {
        JsonSchemaValidationPort validator = mock(JsonSchemaValidationPort.class);
        when(validator.validate(any(), any())).thenReturn(List.of());

        var advisor = advisorWithSchema(SAMPLE_SCHEMA, validator);
        var response = callWith(advisor, "{\"name\":\"Alice\"}");
        assertThat(firstText(response)).isEqualTo("{\"name\":\"Alice\"}");
        verify(validator).validate(SAMPLE_SCHEMA, "{\"name\":\"Alice\"}");
    }

    @Test
    void level2_validatorBlocksWhenErrors() {
        JsonSchemaValidationPort validator = mock(JsonSchemaValidationPort.class);
        when(validator.validate(any(), any())).thenReturn(List.of("$.name: is missing"));

        var advisor = advisorWithSchema(SAMPLE_SCHEMA, validator);
        var response = callWith(advisor, "{\"age\":30}");
        assertThat(firstText(response)).contains("output_schema_violation");
    }

    @Test
    void level2_validatorThrows_degradesToWellFormedness() {
        JsonSchemaValidationPort validator = mock(JsonSchemaValidationPort.class);
        when(validator.validate(any(), any())).thenThrow(new RuntimeException("validator down"));

        var advisor = advisorWithSchema(SAMPLE_SCHEMA, validator);
        // Should not throw — degrades gracefully (LLD-12 §4)
        var response = callWith(advisor, "{\"age\":30}");
        assertThat(firstText(response)).isEqualTo("{\"age\":30}");
    }

    @Test
    void level2_nullSchema_skipsValidatorCall() {
        JsonSchemaValidationPort validator = mock(JsonSchemaValidationPort.class);
        var advisor = advisorWithSchema(null, validator);
        callWith(advisor, "{\"name\":\"Alice\"}");
        verify(validator, never()).validate(any(), any());
    }

    @Test
    void level2_blankSchema_skipsValidatorCall() {
        JsonSchemaValidationPort validator = mock(JsonSchemaValidationPort.class);
        var advisor = advisorWithSchema("   ", validator);
        callWith(advisor, "{\"name\":\"Alice\"}");
        verify(validator, never()).validate(any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static StructuredOutputValidationAdvisor advisorWithSchema(
            String schema, JsonSchemaValidationPort validator) {
        OutputSpec spec = new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, schema);
        return new StructuredOutputValidationAdvisor(spec, validator);
    }

    private static AdvisedResponse callWith(StructuredOutputValidationAdvisor advisor,
                                             String modelOutput) {
        AssistantMessage assistantMessage = new AssistantMessage(modelOutput);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
        AdvisedResponse upstream = new AdvisedResponse(chatResponse, Map.of());

        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any())).thenReturn(upstream);

        AdvisedRequest request = mock(AdvisedRequest.class);
        return advisor.aroundCall(request, chain);
    }

    private static String firstText(AdvisedResponse response) {
        return response.response().getResult().getOutput().getText();
    }
}
