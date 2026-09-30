package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.OutputSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
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
        OutputSpec spec = new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, SAMPLE_SCHEMA);
        var advisor = new StructuredOutputValidationAdvisor(spec);
        assertThat(advisor.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100);
    }

    // ── Level 1: well-formedness ──────────────────────────────────────────────

    @Test
    void level1_validJsonObject_passes() {
        var advisor = advisorWithSchema(SAMPLE_SCHEMA, null);
        var response = callWith(advisor, "{\"name\":\"Alice\"}");
        assertThat(firstText(response)).isEqualTo("{\"name\":\"Alice\"}");
    }

    @Test
    void level1_invalidJson_blocksResponse() {
        var advisor = advisorWithSchema(SAMPLE_SCHEMA, null);
        var response = callWith(advisor, "not json at all");
        assertThat(firstText(response)).contains("output_invalid_json");
    }

    @Test
    void level1_jsonPrimitive_blocksResponse() {
        var advisor = advisorWithSchema(SAMPLE_SCHEMA, null);
        var response = callWith(advisor, "42");
        assertThat(firstText(response)).contains("output_not_json_object");
    }

    @Test
    void level1_blankOutput_blocksResponse() {
        var advisor = advisorWithSchema(SAMPLE_SCHEMA, null);
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
    void outputSpec_rejectsMissingOrBlankSchema() {
        assertThatThrownBy(() -> new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── tool-calling rounds ───────────────────────────────────────────────────

    @Test
    void aRoundThatAsksForToolCallsIsNotTheAnswerAndPassesUntouched() {
        // the advisor sits inside the tool-calling loop, so it sees every model round, not only the last
        AssistantMessage ask = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id-1", "function", "find_orders", "{}"))).build();
        ChatClientResponse upstream = new ChatClientResponse(new ChatResponse(List.of(new Generation(ask))), Map.of());
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(upstream);

        var response = advisorWithSchema(SAMPLE_SCHEMA, null)
                .adviseCall(new ChatClientRequest(new Prompt("q"), Map.of()), chain);

        assertThat(response).isSameAs(upstream);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static StructuredOutputValidationAdvisor advisorWithSchema(
            String schema, JsonSchemaValidationPort validator) {
        OutputSpec spec = new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, schema);
        return new StructuredOutputValidationAdvisor(spec, validator);
    }

    private static ChatClientResponse callWith(StructuredOutputValidationAdvisor advisor,
                                             String modelOutput) {
        AssistantMessage assistantMessage = new AssistantMessage(modelOutput);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
        ChatClientResponse upstream = new ChatClientResponse(chatResponse, Map.of());

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(upstream);

        ChatClientRequest request = new ChatClientRequest(new Prompt("question"), Map.of());
        return advisor.adviseCall(request, chain);
    }

    private static String firstText(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }
}
