package com.springaimcpservercommon.query.ast;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The right-hand side of a {@link FilterNode.Comparison} (LLD-05 §2).
 *
 * <p>Three sources of values are supported:
 * <ul>
 *   <li>{@link ParamRef}: a caller-supplied parameter declared in {@link QueryDefinition#params()}</li>
 *   <li>{@link Literal}: a constant embedded in the query definition</li>
 *   <li>{@link PrincipalAttr}: an attribute from the calling principal — bound server-side, never from model input</li>
 * </ul>
 */
public sealed interface Operand permits Operand.ParamRef, Operand.Literal, Operand.PrincipalAttr {

    /**
     * A reference to a caller-supplied parameter declared in {@link QueryDefinition#params()}.
     *
     * @param paramName name matching a {@link QueryParam#name()}
     */
    record ParamRef(String paramName) implements Operand {
        /** Validates the parameter name. */
        public ParamRef {
            Objects.requireNonNull(paramName, "paramName");
            if (paramName.isBlank()) {
                throw new IllegalArgumentException("paramName must not be blank");
            }
        }
    }

    /**
     * A constant value embedded in the query definition.
     *
     * <p>For scalar operators the value must be a {@code String}, {@code Number}, or {@code Boolean}.
     * For multi-value operators ({@link Operator#IN}, {@link Operator#NOT_IN}, {@link Operator#BETWEEN})
     * the value must be a {@code java.util.List} of scalars.
     *
     * @param value the constant, or {@code null} for an explicit SQL NULL literal
     */
    record Literal(@Nullable Object value) implements Operand {}

    /**
     * An attribute resolved at execution time from the calling
     * {@link com.springaimcpservercommon.core.principal.DaiPrincipal#attributes()}.
     *
     * <p>If the attribute is absent from the principal, the containing predicate evaluates to
     * {@code FALSE} (fail-closed) — the query is never executed with missing policy bindings.
     *
     * <p>Example: {@code PrincipalAttr("tenantId")} resolves to the caller's tenant for
     * a row policy such as {@code Order.tenantId EQ principal.tenantId}.
     *
     * @param attributeName key in {@link com.springaimcpservercommon.core.principal.DaiPrincipal#attributes()}
     */
    record PrincipalAttr(String attributeName) implements Operand {
        /** Validates the attribute name. */
        public PrincipalAttr {
            Objects.requireNonNull(attributeName, "attributeName");
            if (attributeName.isBlank()) {
                throw new IllegalArgumentException("attributeName must not be blank");
            }
        }
    }
}
