package com.springaimcpservercommon.ai.agent;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Output format specification for an agent (LLD-06 §2).
 *
 * @param mode       output mode
 * @param jsonSchema JSON Schema string when mode is {@link Mode#JSON_SCHEMA}; {@code null} otherwise
 */
public record OutputSpec(Mode mode, @Nullable String jsonSchema) {

    /** Free-form text response. */
    public static final OutputSpec TEXT = new OutputSpec(Mode.TEXT, null);

    /** Output mode. */
    public enum Mode {
        /** Free-form text response. */
        TEXT,
        /** Structured JSON response validated against {@link OutputSpec#jsonSchema()}. */
        JSON_SCHEMA
    }

    /** Validates the combination. */
    public OutputSpec {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.JSON_SCHEMA) {
            Objects.requireNonNull(jsonSchema, "jsonSchema is required for JSON_SCHEMA mode");
            if (jsonSchema.isBlank()) {
                throw new IllegalArgumentException("jsonSchema must not be blank for JSON_SCHEMA mode");
            }
        }
    }
}
