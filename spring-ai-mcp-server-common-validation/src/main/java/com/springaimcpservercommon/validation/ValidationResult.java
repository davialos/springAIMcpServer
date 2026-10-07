package com.springaimcpservercommon.validation;

import java.util.List;

/**
 * Outcome of a validation run.
 *
 * @param violations everything reported, in execution order
 * @param executed   ids of the rules that ran, in execution order
 */
public record ValidationResult(List<Violation> violations, List<String> executed) {

    /** Copies the lists. */
    public ValidationResult {
        violations = List.copyOf(violations);
        executed = List.copyOf(executed);
    }

    /**
     * Whether no {@link Severity#ERROR} was reported.
     *
     * @return true when valid
     */
    public boolean isValid() {
        return violations.stream().noneMatch(v -> v.severity() == Severity.ERROR);
    }

    /**
     * Errors only.
     *
     * @return the errors
     */
    public List<Violation> errors() {
        return violations.stream().filter(v -> v.severity() == Severity.ERROR).toList();
    }

    /**
     * Warnings only.
     *
     * @return the warnings
     */
    public List<Violation> warnings() {
        return violations.stream().filter(v -> v.severity() == Severity.WARNING).toList();
    }

    /**
     * Throws when invalid.
     *
     * @throws ValidationException if any error was reported
     */
    public void throwIfInvalid() {
        if (!isValid()) {
            throw new ValidationException(this);
        }
    }
}
