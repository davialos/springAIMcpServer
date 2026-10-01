package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A string, number, integer or boolean value.
 *
 * @param type        JSON type
 * @param format      OpenAPI format ({@code email}, {@code uuid}, {@code date}, {@code date-time}, {@code int64} …)
 * @param constraints validation constraints
 * @param enumValues  allowed values, empty when unrestricted
 * @param example     an example value declared by the project, if any
 */
public record ScalarSchema(ScalarType type, @Nullable String format, Constraints constraints,
                           List<String> enumValues, @Nullable String example) implements Schema {

    /** Compact constructor: defensive copy. */
    public ScalarSchema {
        enumValues = List.copyOf(enumValues);
    }

    /**
     * An unconstrained scalar.
     *
     * @param type   JSON type
     * @param format format or {@code null}
     * @return the schema
     */
    public static ScalarSchema of(ScalarType type, @Nullable String format) {
        return new ScalarSchema(type, format, Constraints.NONE, List.of(), null);
    }

    /**
     * Copy with other constraints.
     *
     * @param newConstraints constraints
     * @return the copy
     */
    public ScalarSchema withConstraints(Constraints newConstraints) {
        return new ScalarSchema(type, format, newConstraints, enumValues, example);
    }

    /**
     * Copy with another format.
     *
     * @param newFormat format
     * @return the copy
     */
    public ScalarSchema withFormat(@Nullable String newFormat) {
        return new ScalarSchema(type, newFormat, constraints, enumValues, example);
    }

    /**
     * Copy with an example value.
     *
     * @param newExample example
     * @return the copy
     */
    public ScalarSchema withExample(@Nullable String newExample) {
        return new ScalarSchema(type, format, constraints, enumValues, newExample);
    }
}
