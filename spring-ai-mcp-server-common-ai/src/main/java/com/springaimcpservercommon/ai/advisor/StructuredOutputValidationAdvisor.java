package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Post-turn advisor that validates the model's output against the declared JSON Schema (LLD-06 §4).
 *
 * <p>Order: {@link Ordered#LOWEST_PRECEDENCE} {@code - 100} — after the tool-calling loop completes
 * but before {@link UsageMeteringAdvisor} so that a validation failure is metered as a complete turn.
 *
 * <p>Only active when {@link OutputSpec#mode()} is {@link OutputSpec.Mode#JSON_SCHEMA}.
 * The advisor validates two levels:
 * <ol>
 *   <li>Well-formedness: the output must parse as a valid JSON object or array.</li>
 *   <li>Schema conformance: if a JSON Schema string is present and a {@link JsonSchemaValidationPort}
 *       was supplied, the output must conform to the schema. When no validator is available,
 *       only well-formedness is enforced (graceful degradation, LLD-12 §4).</li>
 * </ol>
 *
 * <p>On failure the advisor returns a safe synthetic {@link ChatResponse} (no retry, no exception)
 * so the agent runtime degrades gracefully per LLD-12 §4.
 */
@NullMarked
public final class StructuredOutputValidationAdvisor implements CallAroundAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredOutputValidationAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    // Jackson ObjectMapper is instantiated locally — never registered as a bean (ADR-0019).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutputSpec outputSpec;
    private final @Nullable JsonSchemaValidationPort schemaValidator;

    /**
     * Creates the advisor for a specific agent turn (no schema validator — well-formedness only).
     *
     * @param outputSpec the agent's output specification; must be {@link OutputSpec.Mode#JSON_SCHEMA}
     * @throws IllegalArgumentException if the output mode is not JSON_SCHEMA
     */
    public StructuredOutputValidationAdvisor(OutputSpec outputSpec) {
        this(outputSpec, null);
    }

    /**
     * Creates the advisor for a specific agent turn with optional structural schema validation.
     *
     * @param outputSpec      the agent's output specification; must be {@link OutputSpec.Mode#JSON_SCHEMA}
     * @param schemaValidator optional port for JSON Schema conformance checking;
     *                        when {@code null} only Level 1 (well-formedness) is enforced
     * @throws IllegalArgumentException if the output mode is not JSON_SCHEMA
     */
    public StructuredOutputValidationAdvisor(OutputSpec outputSpec,
                                              @Nullable JsonSchemaValidationPort schemaValidator) {
        this.outputSpec = Objects.requireNonNull(outputSpec, "outputSpec");
        if (outputSpec.mode() != OutputSpec.Mode.JSON_SCHEMA) {
            throw new IllegalArgumentException(
                    "StructuredOutputValidationAdvisor requires JSON_SCHEMA mode, got: " + outputSpec.mode());
        }
        this.schemaValidator = schemaValidator;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);
        return validate(response);
    }

    private AdvisedResponse validate(AdvisedResponse response) {
        if (response.response() == null) {
            return response;
        }
        var result = response.response().getResult();
        if (result == null) {
            return response;
        }
        String text = result.getOutput().getText();
        if (text == null || text.isBlank()) {
            LOG.warn("Agent returned blank output in JSON_SCHEMA mode");
            return blocked(response.adviseContext(), "output_empty",
                    "The agent returned an empty response. Please try again.");
        }

        // Level 1: well-formedness check
        try {
            var node = MAPPER.readTree(text);
            // Only JSON objects and arrays are valid structured outputs
            if (!node.isObject() && !node.isArray()) {
                LOG.warn("Agent output is not a JSON object or array in JSON_SCHEMA mode");
                return blocked(response.adviseContext(), "output_not_json_object",
                        "The agent response must be a JSON object or array.");
            }
        } catch (JsonProcessingException e) {
            LOG.warn("Agent output is not valid JSON in JSON_SCHEMA mode: {}", e.getMessage());
            return blocked(response.adviseContext(), "output_invalid_json",
                    "The agent response is not valid JSON. Please try again.");
        }

        // Level 2: schema conformance (structural)
        String schema = outputSpec.jsonSchema();
        if (schema != null && !schema.isBlank()) {
            if (schemaValidator != null) {
                try {
                    List<String> errors = schemaValidator.validate(schema, text);
                    if (!errors.isEmpty()) {
                        LOG.warn("Agent output failed JSON Schema validation: {}", errors);
                        return blocked(response.adviseContext(), "output_schema_violation",
                                "The agent response does not conform to the expected schema.");
                    }
                } catch (Exception e) {
                    // Validator threw unexpectedly — degrade to well-formedness only (LLD-12 §4)
                    LOG.warn("JSON Schema validation error (skipping schema check): {}", e.getMessage());
                }
            } else {
                LOG.debug("JSON Schema validation skipped (no validator; well-formedness only; schema: {} chars)",
                        schema.length());
            }
        }

        return response;
    }

    private static AdvisedResponse blocked(Map<String, Object> adviseContext, String code, String message) {
        AssistantMessage msg = new AssistantMessage("[" + code + "] " + message);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        return new AdvisedResponse(chatResponse, adviseContext);
    }
}
