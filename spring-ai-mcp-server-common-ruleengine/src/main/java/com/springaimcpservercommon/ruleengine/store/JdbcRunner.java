package com.springaimcpservercommon.ruleengine.store;

import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal JDBC plumbing shared by the rule-engine stores: a connection scoped to the schema, a read-write or
 * read-only transaction, and a few statement helpers. Not a general-purpose API.
 */
public final class JdbcRunner {

    /** Work done on one connection inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        /**
         * Runs the work.
         *
         * @param connection the transaction's connection
         * @return the result
         * @throws SQLException on a database error
         */
        T run(Connection connection) throws SQLException;
    }

    /** Maps the current row of a result set. */
    @FunctionalInterface
    public interface RowMapper<T> {
        /**
         * Maps one row.
         *
         * @param rs result set positioned on the row
         * @return the mapped value
         * @throws SQLException on a database error
         */
        T map(ResultSet rs) throws SQLException;
    }

    private final DataSource dataSource;
    private final String schema;

    /**
     * Creates the runner.
     *
     * @param dataSource data source (not closed)
     * @param schema     schema holding the tables
     */
    public JdbcRunner(DataSource dataSource, String schema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /**
     * Runs work in a read-write transaction (READ COMMITTED); commits on success, rolls back on any exception.
     *
     * @param work the work
     * @param <T>  result type
     * @return the work's result
     */
    public <T> T write(Work<T> work) {
        return run(work, false);
    }

    /**
     * Runs work in a read-only transaction.
     *
     * @param work the work
     * @param <T>  result type
     * @return the work's result
     */
    public <T> T read(Work<T> work) {
        return run(work, true);
    }

    private <T> T run(Work<T> work, boolean readOnly) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            c.setReadOnly(readOnly);
            c.setSchema(schema);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setReadOnly(false);
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StoreFailure(e.getSQLState(), "rule store failed: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a query and maps all rows.
     *
     * @param c      connection
     * @param sql    SQL with {@code ?} markers
     * @param mapper row mapper
     * @param params parameters (UUID, String, Integer, Long, Boolean, Instant-as-Timestamp, or null)
     * @param <T>    row type
     * @return the rows
     * @throws SQLException on a database error
     */
    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, @Nullable Object... params)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        }
    }

    /**
     * Executes a query expected to return at most one row.
     *
     * @param c      connection
     * @param sql    SQL
     * @param mapper row mapper
     * @param params parameters
     * @param <T>    row type
     * @return the row, or {@code null} when none
     * @throws SQLException on a database error
     */
    public static <T> @Nullable T queryOne(Connection c, String sql, RowMapper<T> mapper, @Nullable Object... params)
            throws SQLException {
        List<T> rows = query(c, sql, mapper, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Executes an insert/update/delete.
     *
     * @param c      connection
     * @param sql    SQL
     * @param params parameters
     * @return rows affected
     * @throws SQLException on a database error
     */
    public static int update(Connection c, String sql, @Nullable Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, @Nullable Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof java.time.Instant t) {
                ps.setTimestamp(i + 1, java.sql.Timestamp.from(t));
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    /** A database error, with the SQLSTATE so callers can recognise constraint violations. */
    public static final class StoreFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final @Nullable String sqlState;

        public StoreFailure(@Nullable String sqlState, String message, Throwable cause) {
            super(message, cause);
            this.sqlState = sqlState;
        }

        /**
         * The SQLSTATE, for example {@code 23505} (unique violation) or {@code 23514} (check violation).
         *
         * @return the state, or {@code null}
         */
        public @Nullable String sqlState() {
            return sqlState;
        }

        /**
         * Whether a unique constraint or index was violated.
         *
         * @return {@code true} for SQLSTATE 23505
         */
        public boolean uniqueViolation() {
            return "23505".equals(sqlState);
        }
    }
}
