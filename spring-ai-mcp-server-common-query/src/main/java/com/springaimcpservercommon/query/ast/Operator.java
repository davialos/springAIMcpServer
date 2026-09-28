package com.springaimcpservercommon.query.ast;

/**
 * Comparison operators for dynamic query predicates (LLD-05 §2).
 *
 * <p>Operators fall into three groups:
 * <ul>
 *   <li><em>Unary</em> ({@link #IS_NULL}, {@link #NOT_NULL}): no right-hand-side operand.</li>
 *   <li><em>Multi-value</em> ({@link #IN}, {@link #NOT_IN}, {@link #BETWEEN}): operand must
 *       be a {@link Operand.Literal} whose value is a {@code List}; BETWEEN requires exactly
 *       two elements.</li>
 *   <li><em>Scalar</em>: all others.</li>
 * </ul>
 */
public enum Operator {
    /** Equality ({@code =}). */
    EQ,
    /** Inequality ({@code <>}). */
    NE,
    /** Less-than ({@code <}). */
    LT,
    /** Less-than-or-equal ({@code <=}). */
    LE,
    /** Greater-than ({@code >}). */
    GT,
    /** Greater-than-or-equal ({@code >=}). */
    GE,
    /** Value present in a list (max {@value com.springaimcpservercommon.query.validation.QueryValidator#MAX_IN_SIZE} elements). */
    IN,
    /** Value absent from a list. */
    NOT_IN,
    /**
     * String prefix match ({@code LIKE 'prefix%'}) — wildcards in the value are escaped;
     * the {@code %} suffix is appended by the compiler.
     */
    LIKE_PREFIX,
    /**
     * Case-insensitive substring match ({@code LOWER(col) LIKE '%value%'}) — wildcards escaped.
     */
    CONTAINS_CI,
    /** Attribute is SQL {@code NULL}. */
    IS_NULL,
    /** Attribute is not SQL {@code NULL}. */
    NOT_NULL,
    /**
     * Inclusive range ({@code BETWEEN lo AND hi}). The operand must be a two-element
     * {@code List} {@code [lo, hi]}.
     */
    BETWEEN;

    /** Whether this operator takes no right-hand-side operand. */
    public boolean isUnary() {
        return this == IS_NULL || this == NOT_NULL;
    }

    /** Whether this operator requires a list operand (IN / NOT_IN / BETWEEN). */
    public boolean isMultiValue() {
        return this == IN || this == NOT_IN || this == BETWEEN;
    }

    /** Whether this operator applies only to strings. */
    public boolean isStringOnly() {
        return this == LIKE_PREFIX || this == CONTAINS_CI;
    }
}
