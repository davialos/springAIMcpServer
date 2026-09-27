package com.springaimcpservercommon.core.policy;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when a policy document is not valid JSON or violates schema version 1. Carries every error found (not
 * only the first), each prefixed with a JSON path. Messages never echo override values, only keys and field names.
 */
public final class PolicyValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    /**
     * Creates the exception.
     *
     * @param errors validation errors (at least one)
     */
    public PolicyValidationException(List<String> errors) {
        super("invalid policy document: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * All validation errors.
     *
     * @return errors in document order
     */
    public List<String> errors() {
        return errors;
    }
}
