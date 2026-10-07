package com.springaimcpservercommon.validation;

/** Thrown by {@link ValidationResult#throwIfInvalid()} and {@link Validator#validateOrThrow}. */
public class ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ValidationResult result;

    /**
     * Creates the exception.
     *
     * @param result the failed result
     */
    public ValidationException(ValidationResult result) {
        super("Validation failed: " + result.errors().stream().map(Violation::code).toList());
        this.result = result;
    }

    /**
     * The failed result.
     *
     * @return the result
     */
    public ValidationResult result() {
        return result;
    }
}
