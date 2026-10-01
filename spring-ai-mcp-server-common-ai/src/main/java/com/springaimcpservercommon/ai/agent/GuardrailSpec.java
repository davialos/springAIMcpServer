package com.springaimcpservercommon.ai.agent;

import com.springaimcpservercommon.core.guard.InputValidationPolicy;

import java.util.List;
import java.util.Objects;

/**
 * Input and output guardrail configuration for an agent (LLD-06 §8).
 *
 * @param maxInputChars      maximum characters in the user's input message; 0 = unlimited
 * @param blockedPatterns    regex patterns that, if matched in input, cause an immediate rejection
 * @param piiRedactionInput  whether to redact PII from user input before sending to the model
 * @param piiRedactionOutput whether to redact PII from model output before returning to the caller
 * @param topicAllowList     whitelist of allowed topics; empty = all topics allowed
 * @param maxOutputChars     maximum characters in the model's response; 0 = unlimited
 * @param inputValidation    prompt validation (malicious content, business scope); combined with the host floor
 */
public record GuardrailSpec(
        int maxInputChars,
        List<String> blockedPatterns,
        boolean piiRedactionInput,
        boolean piiRedactionOutput,
        List<String> topicAllowList,
        int maxOutputChars,
        InputValidationPolicy inputValidation) {

    /** Default: no guardrails active. */
    public static final GuardrailSpec OFF = new GuardrailSpec(0, List.of(), false, false, List.of(), 0);

    /** Validates and copies collections. */
    public GuardrailSpec {
        if (maxInputChars < 0) throw new IllegalArgumentException("maxInputChars must be >= 0");
        if (maxOutputChars < 0) throw new IllegalArgumentException("maxOutputChars must be >= 0");
        Objects.requireNonNull(blockedPatterns, "blockedPatterns");
        Objects.requireNonNull(topicAllowList, "topicAllowList");
        Objects.requireNonNull(inputValidation, "inputValidation");
        blockedPatterns = List.copyOf(blockedPatterns);
        topicAllowList = List.copyOf(topicAllowList);
    }

    /**
     * Guardrails without agent-level prompt validation (the host floor still applies).
     *
     * @param maxInputChars      maximum characters in the user's input message; 0 = unlimited
     * @param blockedPatterns    regex patterns that reject the input
     * @param piiRedactionInput  redact PII from user input before sending to the model
     * @param piiRedactionOutput redact PII from model output before returning to the caller
     * @param topicAllowList     allowed topics; empty = all
     * @param maxOutputChars     maximum characters in the model's response; 0 = unlimited
     */
    public GuardrailSpec(int maxInputChars, List<String> blockedPatterns, boolean piiRedactionInput,
                         boolean piiRedactionOutput, List<String> topicAllowList, int maxOutputChars) {
        this(maxInputChars, blockedPatterns, piiRedactionInput, piiRedactionOutput, topicAllowList, maxOutputChars,
                InputValidationPolicy.OFF);
    }
}
