package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Validation constraints on a scalar (from Jakarta Validation annotations or OpenAPI keywords).
 *
 * @param minLength minimum string length
 * @param maxLength maximum string length
 * @param minimum   inclusive numeric minimum
 * @param maximum   inclusive numeric maximum
 * @param pattern   regular expression the value must match
 * @param temporal  for dates: whether the value must lie in the past or the future
 */
public record Constraints(@Nullable Long minLength, @Nullable Long maxLength,
                          @Nullable BigDecimal minimum, @Nullable BigDecimal maximum,
                          @Nullable String pattern, @Nullable Temporal temporal) {

    /** No constraints. */
    public static final Constraints NONE = new Constraints(null, null, null, null, null, null);

    /** Direction of a temporal constraint. */
    public enum Temporal { PAST, FUTURE }

    /**
     * Combines two constraint sets; a value set in {@code other} wins.
     *
     * @param other constraints to overlay
     * @return merged constraints
     */
    public Constraints overlay(Constraints other) {
        return new Constraints(
                other.minLength != null ? other.minLength : minLength,
                other.maxLength != null ? other.maxLength : maxLength,
                other.minimum != null ? other.minimum : minimum,
                other.maximum != null ? other.maximum : maximum,
                other.pattern != null ? other.pattern : pattern,
                other.temporal != null ? other.temporal : temporal);
    }

    /**
     * Whether no constraint is set.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return equals(NONE);
    }
}
