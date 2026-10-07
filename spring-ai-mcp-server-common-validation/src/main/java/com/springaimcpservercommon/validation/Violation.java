package com.springaimcpservercommon.validation;

import org.jspecify.annotations.Nullable;

/**
 * One failed check.
 *
 * @param ruleId   id of the rule that produced it
 * @param field    the offending field or path, if any
 * @param code     stable machine-readable code
 * @param message  human-readable message (never include secrets or row data)
 * @param severity severity
 */
public record Violation(String ruleId, @Nullable String field, String code, String message, Severity severity) {

    /**
     * Error violation.
     *
     * @param ruleId  rule id
     * @param field   field
     * @param code    code
     * @param message message
     * @return the violation
     */
    public static Violation error(String ruleId, @Nullable String field, String code, String message) {
        return new Violation(ruleId, field, code, message, Severity.ERROR);
    }

    /**
     * Warning violation.
     *
     * @param ruleId  rule id
     * @param field   field
     * @param code    code
     * @param message message
     * @return the violation
     */
    public static Violation warning(String ruleId, @Nullable String field, String code, String message) {
        return new Violation(ruleId, field, code, message, Severity.WARNING);
    }
}
