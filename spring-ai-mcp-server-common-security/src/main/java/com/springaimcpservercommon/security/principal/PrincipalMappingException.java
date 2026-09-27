package com.springaimcpservercommon.security.principal;

import java.io.Serial;

/**
 * Thrown when an authentication cannot be turned into a framework principal. Callers treat it as "not authenticated"
 * (fail closed). Messages never contain token contents or claim values.
 */
public class PrincipalMappingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message safe, non-sensitive message
     */
    public PrincipalMappingException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message safe, non-sensitive message
     * @param cause   the cause
     */
    public PrincipalMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
