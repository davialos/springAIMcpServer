package com.springaimcpservercommon.ai.advisor;

import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Port: validates a JSON document string against a JSON Schema string (LLD-06 §4, Level 2).
 *
 * <p>The default implementation in the {@code autoconfigure} module uses
 * {@code com.networknt:json-schema-validator} when it is present on the host's classpath
 * (activated via {@code @ConditionalOnClass}). When absent, the autoconfigure module registers
 * a no-op implementation that always returns an empty list, meaning only well-formedness (Level 1)
 * is enforced.
 *
 * <p>Hosts may replace either default by registering their own bean of this type.
 * Implementations must be thread-safe.
 */
@NullMarked
@FunctionalInterface
public interface JsonSchemaValidationPort {

    /**
     * Validates {@code jsonDocument} against {@code jsonSchema}.
     *
     * @param jsonSchema   the JSON Schema string (draft-07 format expected)
     * @param jsonDocument the JSON string to validate (must already be well-formed)
     * @return immutable list of human-readable error messages; empty list means the document is valid
     */
    List<String> validate(String jsonSchema, String jsonDocument);
}
