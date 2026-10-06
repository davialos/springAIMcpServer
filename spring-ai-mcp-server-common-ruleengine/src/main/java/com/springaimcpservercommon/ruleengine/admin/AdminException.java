package com.springaimcpservercommon.ruleengine.admin;

import java.util.List;

/**
 * A request the authoring services refuse. The {@link #code()} is stable and machine-readable; the admin API maps the
 * subclass to an HTTP status (404, 409, 422).
 */
public abstract sealed class AdminException extends RuntimeException
        permits AdminException.NotFound, AdminException.Conflict, AdminException.Invalid {

    private static final long serialVersionUID = 1L;

    private final String code;

    AdminException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Stable error code, for example {@code four_eyes} or {@code parameter_in_use}.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /** The subject does not exist (or belongs to another tenant). */
    public static final class NotFound extends AdminException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param what what was not found, for example {@code rule}
         */
        public NotFound(String what) {
            super("not_found", what + " not found");
        }
    }

    /** The request is valid but conflicts with the current state (wrong lifecycle state, duplicate, in use). */
    public static final class Conflict extends AdminException {
        private static final long serialVersionUID = 1L;
        private final List<String> details;

        /**
         * Creates the exception.
         *
         * @param code    stable code
         * @param message explanation
         * @param details what the conflict is with (for example the codes of rules that use a parameter)
         */
        public Conflict(String code, String message, List<String> details) {
            super(code, message);
            this.details = List.copyOf(details);
        }

        /**
         * Items the request conflicts with.
         *
         * @return details, possibly empty
         */
        public List<String> details() {
            return details;
        }
    }

    /** The content failed validation. */
    public static final class Invalid extends AdminException {
        private static final long serialVersionUID = 1L;
        private final List<String> violations;

        /**
         * Creates the exception.
         *
         * @param code       stable code
         * @param violations one entry per problem (field: reason)
         */
        public Invalid(String code, List<String> violations) {
            super(code, String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        /**
         * Convenience for a single problem.
         *
         * @param code       stable code
         * @param violation  the problem
         */
        public Invalid(String code, String violation) {
            this(code, List.of(violation));
        }

        /**
         * The individual problems.
         *
         * @return violations
         */
        public List<String> violations() {
            return violations;
        }
    }
}
