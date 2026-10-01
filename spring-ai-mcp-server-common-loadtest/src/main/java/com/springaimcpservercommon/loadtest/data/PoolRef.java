package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

/**
 * A real-data pool: the values of one database column.
 *
 * @param schema database schema, or {@code null} for the connection's default
 * @param table  table name as stored in the database
 * @param column column name as stored in the database
 */
public record PoolRef(@Nullable String schema, String table, String column) {

    /**
     * Pool key used in {@code data/real.json} and in generated field specs: {@code table.column}, prefixed with
     * the schema when it is not a default one.
     *
     * @return key
     */
    public String key() {
        boolean defaultSchema = schema == null || schema.isBlank() || schema.equalsIgnoreCase("public")
                || schema.equalsIgnoreCase("dbo");
        return (defaultSchema ? "" : schema + ".") + table + "." + column;
    }

    /**
     * Parses {@code [schema.]table.column}.
     *
     * @param ref reference text
     * @return the pool
     * @throws IllegalArgumentException when fewer than two segments are given
     */
    public static PoolRef parse(String ref) {
        String[] parts = ref.trim().split("\\.");
        return switch (parts.length) {
            case 2 -> new PoolRef(null, parts[0], parts[1]);
            case 3 -> new PoolRef(parts[0], parts[1], parts[2]);
            default -> throw new IllegalArgumentException("expected table.column or schema.table.column: " + ref);
        };
    }
}
