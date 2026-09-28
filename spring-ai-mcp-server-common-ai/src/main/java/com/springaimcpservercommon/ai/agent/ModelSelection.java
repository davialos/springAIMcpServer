package com.springaimcpservercommon.ai.agent;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Model selection and parameters for an agent (LLD-06 §2, §6).
 *
 * @param providerId   Spring AI model provider id (e.g. {@code openai}, {@code anthropic})
 * @param modelName    provider-specific model identifier (e.g. {@code gpt-4o})
 * @param temperature  sampling temperature, 0.0–2.0; {@code null} uses the provider default
 * @param maxTokens    maximum output tokens per turn; {@code null} uses the provider default
 * @param fallback     optional fallback selection used when the primary model circuit is open
 */
public record ModelSelection(
        String providerId,
        String modelName,
        @Nullable Double temperature,
        @Nullable Integer maxTokens,
        @Nullable ModelSelection fallback) {

    /** Validates required fields. */
    public ModelSelection {
        Objects.requireNonNull(providerId, "providerId");
        if (providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
        Objects.requireNonNull(modelName, "modelName");
        if (modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be in [0.0, 2.0]: " + temperature);
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1: " + maxTokens);
        }
    }
}
