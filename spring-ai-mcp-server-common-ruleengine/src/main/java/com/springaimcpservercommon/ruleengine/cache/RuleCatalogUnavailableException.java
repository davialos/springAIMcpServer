package com.springaimcpservercommon.ruleengine.cache;

/** The rule store cannot be read and no earlier snapshot exists to serve. */
public final class RuleCatalogUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message message
     * @param cause   store failure
     */
    public RuleCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
