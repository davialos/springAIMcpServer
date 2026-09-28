package com.springaimcpservercommon.query.criteria;

/**
 * Thrown when the {@link CriteriaCompiler} cannot compile a query definition.
 */
public final class CriteriaCompilationException extends RuntimeException {

    /**
     * Creates the exception with a message.
     *
     * @param message description
     */
    public CriteriaCompilationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and a cause.
     *
     * @param message description
     * @param cause   underlying cause
     */
    public CriteriaCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
