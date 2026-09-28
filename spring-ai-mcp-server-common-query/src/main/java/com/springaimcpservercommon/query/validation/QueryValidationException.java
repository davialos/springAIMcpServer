package com.springaimcpservercommon.query.validation;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a {@link com.springaimcpservercommon.query.ast.QueryDefinition} fails validation (LLD-05 §3).
 *
 * <p>Publish-time validation failures are returned as HTTP 422; runtime failures (e.g. catalog drift)
 * suspend the query rather than returning an error to the caller.
 */
public final class QueryValidationException extends RuntimeException {

    private final List<String> violations;

    /**
     * Creates the exception with a list of human-readable violation messages.
     *
     * @param violations non-empty list of violation messages
     */
    public QueryValidationException(List<String> violations) {
        super("Query validation failed (" + violations.size() + " violation(s)): " + violations);
        Objects.requireNonNull(violations, "violations");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations list must not be empty");
        }
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the list of human-readable violation messages.
     *
     * @return violations (non-empty, unmodifiable)
     */
    public List<String> violations() {
        return violations;
    }
}
