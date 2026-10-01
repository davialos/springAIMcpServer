package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A table as reported by JDBC metadata.
 *
 * @param schema     schema name
 * @param name       table name
 * @param columns    column name → JDBC type name (e.g. {@code int8}, {@code uuid}, {@code varchar})
 * @param primaryKey primary-key columns in key order
 */
public record DbTable(@Nullable String schema, String name, Map<String, String> columns, List<String> primaryKey) {

    /** Compact constructor: ordered, unmodifiable copies. */
    public DbTable {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        primaryKey = List.copyOf(primaryKey);
    }
}
