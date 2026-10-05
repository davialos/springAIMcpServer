package com.springaimcpservercommon.ruleengine.cel;

/** A CEL expression failed to parse or type-check against the parameter library. */
public final class RuleCompilationException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the compiler's issue text (positions and reasons; never contains fact values)
     */
    public RuleCompilationException(String message) {
        super(message);
    }
}
