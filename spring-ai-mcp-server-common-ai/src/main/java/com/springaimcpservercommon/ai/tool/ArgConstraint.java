package com.springaimcpservercommon.ai.tool;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A server-side constraint on a tool argument (LLD-07 §2, §3 step 3).
 *
 * <p>Constraints are enforced by {@link SecuredToolCallback} <em>after</em> parsing the model's
 * input JSON. The model cannot override them: if the argument is {@link ConstraintKind#PRINCIPAL_ATTR},
 * the model's supplied value is silently replaced with the server-resolved principal attribute value.
 *
 * <p>Example: {@code customerId PRINCIPAL_ATTR="customerId"} ensures the model cannot request data
 * for a different customer regardless of what it puts in the input JSON.
 *
 * @param kind           constraint type
 * @param principalAttr  principal attribute name when kind is {@link ConstraintKind#PRINCIPAL_ATTR}
 * @param literalValue   literal value when kind is {@link ConstraintKind#LITERAL}
 * @param minValue       minimum numeric value when kind is {@link ConstraintKind#RANGE}
 * @param maxValue       maximum numeric value when kind is {@link ConstraintKind#RANGE}
 */
public record ArgConstraint(
        ConstraintKind kind,
        @Nullable String principalAttr,
        @Nullable Object literalValue,
        @Nullable Number minValue,
        @Nullable Number maxValue) {

    /** How the argument is constrained. */
    public enum ConstraintKind {
        /**
         * The argument value is replaced with the resolved value of a
         * {@link com.springaimcpservercommon.core.principal.DaiPrincipal} attribute.
         */
        PRINCIPAL_ATTR,
        /** The argument value is pinned to a literal constant regardless of the model's input. */
        LITERAL,
        /** The argument value is clamped to [minValue, maxValue]. */
        RANGE
    }

    /** Validates the constraint. */
    public ArgConstraint {
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case PRINCIPAL_ATTR -> {
                if (principalAttr == null || principalAttr.isBlank()) {
                    throw new IllegalArgumentException("principalAttr is required for PRINCIPAL_ATTR constraint");
                }
            }
            case RANGE -> {
                if (minValue == null || maxValue == null) {
                    throw new IllegalArgumentException("minValue and maxValue are required for RANGE constraint");
                }
            }
            case LITERAL -> {}
        }
    }

    /**
     * Factory: a PRINCIPAL_ATTR constraint that replaces the argument with a principal attribute.
     *
     * @param principalAttr key in {@link com.springaimcpservercommon.core.principal.DaiPrincipal#attributes()}
     * @return the constraint
     */
    public static ArgConstraint principalAttr(String principalAttr) {
        return new ArgConstraint(ConstraintKind.PRINCIPAL_ATTR, principalAttr, null, null, null);
    }

    /**
     * Factory: a LITERAL constraint that pins the argument to a constant.
     *
     * @param value constant value
     * @return the constraint
     */
    public static ArgConstraint literal(@Nullable Object value) {
        return new ArgConstraint(ConstraintKind.LITERAL, null, value, null, null);
    }

    /**
     * Factory: a RANGE constraint.
     *
     * @param min minimum value (inclusive)
     * @param max maximum value (inclusive)
     * @return the constraint
     */
    public static ArgConstraint range(Number min, Number max) {
        return new ArgConstraint(ConstraintKind.RANGE, null, null,
                Objects.requireNonNull(min, "min"), Objects.requireNonNull(max, "max"));
    }
}
