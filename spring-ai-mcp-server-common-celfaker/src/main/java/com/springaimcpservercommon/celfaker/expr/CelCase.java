package com.springaimcpservercommon.celfaker.expr;

import java.util.Map;

/**
 * One input combination for an expression and the result the real CEL runtime produced for it.
 *
 * @param expression CEL text
 * @param inputs     value per CEL name
 * @param expected   {@code true}, {@code false} or {@code error}
 * @param detail     error message when {@code expected} is {@code error}, else {@code null}
 */
public record CelCase(String expression, Map<String, Object> inputs, String expected, String detail) {

    /**
     * Whether the expression evaluated to true.
     *
     * @return true for a satisfying input
     */
    public boolean isTrue() {
        return "true".equals(expected);
    }

    /**
     * Whether the expression evaluated to false.
     *
     * @return true for a violating input
     */
    public boolean isFalse() {
        return "false".equals(expected);
    }
}
