package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A real-data pool: the values of one database column, or the first column of a read-only query.
 *
 * @param schema database schema, or {@code null} for the connection's default
 * @param table  table name as stored in the database ({@value #QUERY_TABLE} for a query pool)
 * @param column column name as stored in the database (a short stable id of the SQL for a query pool)
 * @param sql    the {@code SELECT} behind a query pool, or {@code null} for a table column
 */
public record PoolRef(@Nullable String schema, String table, String column, @Nullable String sql) {

    /** Prefix of a binding that names a query instead of a column: {@code sql:SELECT id FROM orders WHERE …}. */
    public static final String QUERY_PREFIX = "sql:";

    static final String QUERY_TABLE = "sql";

    /**
     * A table column.
     *
     * @param schema database schema, or {@code null}
     * @param table  table name
     * @param column column name
     */
    public PoolRef(@Nullable String schema, String table, String column) {
        this(schema, table, column, null);
    }

    /**
     * A pool filled by a query: lets a test pick rows in the state an API needs (orders still {@code NEW},
     * customers with a payment method) instead of any row. The first selected column is the value.
     *
     * @param sql a single read-only {@code SELECT} / {@code WITH} statement
     * @return the pool; its key is {@code sql:<8 hex>} of the normalised text
     * @throws IllegalArgumentException when the statement is not a single read-only query
     */
    public static PoolRef query(String sql) {
        String normalised = DatabaseSampler.requireReadOnlySelect(sql);
        return new PoolRef(null, QUERY_TABLE, fingerprint(normalised), normalised);
    }

    /**
     * Whether the pool is a query rather than a table column.
     *
     * @return {@code true} for {@link #query(String)} pools
     */
    public boolean isQuery() {
        return sql != null;
    }

    /**
     * Pool key used in {@code data/real.json} and in generated field specs: {@code table.column}, prefixed with
     * the schema when it is not a default one; {@code sql:<id>} for a query pool.
     *
     * @return key
     */
    public String key() {
        if (isQuery()) {
            return QUERY_PREFIX + column;
        }
        return tableKey() + "." + column;
    }

    /**
     * Key of the table behind the pool: values of pools with the same table key that were sampled together come
     * from the same rows (see {@code DatabaseSampler#sampleRows}). {@code null} for a query pool.
     *
     * @return {@code [schema.]table}, or {@code null}
     */
    public @Nullable String tableKeyOrNull() {
        return isQuery() ? null : tableKey();
    }

    private String tableKey() {
        boolean defaultSchema = schema == null || schema.isBlank() || schema.equalsIgnoreCase("public")
                || schema.equalsIgnoreCase("dbo");
        return defaultSchema ? table : schema + "." + table;
    }

    /**
     * Parses {@code [schema.]table.column} or {@code sql:SELECT …}.
     *
     * @param ref reference text
     * @return the pool
     * @throws IllegalArgumentException when fewer than two segments are given, or the query is not read-only
     */
    public static PoolRef parse(String ref) {
        String trimmed = ref.trim();
        if (trimmed.regionMatches(true, 0, QUERY_PREFIX, 0, QUERY_PREFIX.length())) {
            return query(trimmed.substring(QUERY_PREFIX.length()));
        }
        String[] parts = trimmed.split("\\.");
        return switch (parts.length) {
            case 2 -> new PoolRef(null, parts[0], parts[1]);
            case 3 -> new PoolRef(parts[0], parts[1], parts[2]);
            default -> throw new IllegalArgumentException("expected table.column, schema.table.column or sql:SELECT …: "
                    + ref);
        };
    }

    private static String fingerprint(String normalised) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
