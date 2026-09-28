package com.springaimcpservercommon.ai.guard;

/**
 * Thrown by {@link AiWriteGuardIntegrator} when a Hibernate flush detects a mutating operation
 * that was not explicitly permitted during an AI read scope (ADR-0014).
 *
 * <p>Causes the ongoing transaction to roll back and propagates to the tool bridge as an
 * {@code execution_error} envelope rather than leaking implementation details to the model.
 */
public final class AiWriteViolationException extends RuntimeException {

    /**
     * Creates the exception with a descriptive message.
     *
     * @param entityName  simple class name of the entity that was mutated
     * @param operation   the mutating operation ({@code INSERT}, {@code UPDATE}, {@code DELETE})
     */
    public AiWriteViolationException(String entityName, String operation) {
        super("AI read scope: illegal " + operation + " on " + entityName
                + ". AI tools must not write — only PROPOSE changes (ADR-0014).");
    }
}
