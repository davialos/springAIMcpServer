package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A table as reported by JDBC metadata, or as declared by the project's DDL scripts.
 *
 * @param schema     schema name
 * @param name       table name
 * @param columns    column name → JDBC type name (e.g. {@code int8}, {@code uuid}, {@code varchar})
 * @param primaryKey primary-key columns in key order
 * @param foreignKeys column → the column it references (single-column foreign keys)
 * @param columnSizes column → declared size (character length for text columns)
 * @param uniqueColumns columns with a single-column unique index or constraint
 */
public record DbTable(@Nullable String schema, String name, Map<String, String> columns, List<String> primaryKey,
                      Map<String, PoolRef> foreignKeys, Map<String, Integer> columnSizes,
                      Set<String> uniqueColumns) {

    /** Compact constructor: ordered, unmodifiable copies. */
    public DbTable {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        primaryKey = List.copyOf(primaryKey);
        foreignKeys = Collections.unmodifiableMap(new LinkedHashMap<>(foreignKeys));
        columnSizes = Collections.unmodifiableMap(new LinkedHashMap<>(columnSizes));
        uniqueColumns = Collections.unmodifiableSet(new LinkedHashSet<>(uniqueColumns));
    }

    /**
     * A table without foreign keys, sizes or unique columns.
     *
     * @param schema     schema name
     * @param name       table name
     * @param columns    column → type name
     * @param primaryKey primary-key columns
     */
    public DbTable(@Nullable String schema, String name, Map<String, String> columns, List<String> primaryKey) {
        this(schema, name, columns, primaryKey, Map.of(), Map.of(), Set.of());
    }
}
