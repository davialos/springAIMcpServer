package com.springaimcpservercommon.security.authz.condition;

import java.io.Serial;

/**
 * Thrown when grant conditions are malformed or use unknown attributes/operators. Grants with such conditions never
 * match (fail closed). Messages describe the structural problem only, never attribute values.
 */
public class ConditionParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message structural problem
     */
    public ConditionParseException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message structural problem
     * @param cause   cause
     */
    public ConditionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
