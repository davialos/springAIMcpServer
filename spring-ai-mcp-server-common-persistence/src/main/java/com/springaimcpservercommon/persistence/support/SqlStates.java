package com.springaimcpservercommon.persistence.support;

import org.jspecify.annotations.Nullable;

import java.sql.SQLException;

/**
 * Classifies database failures by PostgreSQL SQLSTATE, walking the cause chain (the same failure surfaces as a
 * Spring {@code DataAccessException}, a Jakarta {@code PersistenceException} or a Hibernate exception depending on
 * where it is thrown).
 */
public final class SqlStates {

    /** {@code unique_violation}. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** {@code check_violation}. */
    public static final String CHECK_VIOLATION = "23514";

    /** {@code insufficient_privilege} (raised by the append-only triggers). */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";

    private SqlStates() {
    }

    /**
     * Finds the first SQLSTATE in the cause chain.
     *
     * @param failure any throwable
     * @return the SQLSTATE, or {@code null} if none is present
     */
    public static @Nullable String sqlState(Throwable failure) {
        @Nullable Throwable current = failure;
        int guard = 0;
        while (current != null && guard++ < 32) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Whether the failure is a unique-constraint violation.
     *
     * @param failure any throwable
     * @return {@code true} for SQLSTATE 23505
     */
    public static boolean isUniqueViolation(Throwable failure) {
        return UNIQUE_VIOLATION.equals(sqlState(failure));
    }
}
