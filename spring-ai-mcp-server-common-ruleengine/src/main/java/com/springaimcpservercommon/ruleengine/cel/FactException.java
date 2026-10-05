package com.springaimcpservercommon.ruleengine.cel;

/** A fact needed by an expression is absent or has the wrong type. */
public final class FactException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception.
     *
     * @param code    {@code MISSING_PARAMETER} or {@code INVALID_PARAMETER}
     * @param message names the parameter (never its value)
     */
    public FactException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * The machine-readable error code.
     *
     * @return {@code MISSING_PARAMETER} or {@code INVALID_PARAMETER}
     */
    public String code() {
        return code;
    }
}
