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
 * @param schema               schema name
 * @param name                 table name
 * @param columns              column name → JDBC type name (e.g. {@code int8}, {@code uuid}, {@code varchar})
 * @param primaryKey           primary-key columns in key order
 * @param foreignKeys          column → the column it references (single-column foreign keys)
 * @param columnSizes          column → declared size (character length for text columns)
 * @param uniqueColumns        columns with a single-column unique index or constraint
 * @param compositeForeignKeys multi-column foreign keys: their columns must be filled from the same parent row
 * @param requiredColumns      {@code NOT NULL} columns without a default (an insert must give them a value)
 * @param generatedColumns     columns the database fills itself (identity, auto-increment, serial, a default)
 */
public record DbTable(@Nullable String schema, String name, Map<String, String> columns, List<String> primaryKey,
                      Map<String, PoolRef> foreignKeys, Map<String, Integer> columnSizes,
                      Set<String> uniqueColumns, List<CompositeForeignKey> compositeForeignKeys,
                      Set<String> requiredColumns, Set<String> generatedColumns) {

    /**
     * A multi-column foreign key. The target pool's column is the referenced columns joined with commas
     * ({@code order_lines.order_id,line_no}); sampled values are tuples in that order.
     *
     * @param columns local columns, in the order of the referenced columns
     * @param target  the referenced table, its columns joined with commas
     */
    public record CompositeForeignKey(List<String> columns, PoolRef target) {

        /** Compact constructor: defensive copy. */
        public CompositeForeignKey {
            columns = List.copyOf(columns);
        }
    }

    /** Compact constructor: ordered, unmodifiable copies. */
    public DbTable {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        primaryKey = List.copyOf(primaryKey);
        foreignKeys = Collections.unmodifiableMap(new LinkedHashMap<>(foreignKeys));
        columnSizes = Collections.unmodifiableMap(new LinkedHashMap<>(columnSizes));
        uniqueColumns = Collections.unmodifiableSet(new LinkedHashSet<>(uniqueColumns));
        compositeForeignKeys = List.copyOf(compositeForeignKeys);
        requiredColumns = Collections.unmodifiableSet(new LinkedHashSet<>(requiredColumns));
        generatedColumns = Collections.unmodifiableSet(new LinkedHashSet<>(generatedColumns));
    }

    /**
     * A table without composite keys or nullability facts.
     *
     * @param schema        schema name
     * @param name          table name
     * @param columns       column → type name
     * @param primaryKey    primary-key columns
     * @param foreignKeys   single-column foreign keys
     * @param columnSizes   column sizes
     * @param uniqueColumns unique columns
     */
    public DbTable(@Nullable String schema, String name, Map<String, String> columns, List<String> primaryKey,
                   Map<String, PoolRef> foreignKeys, Map<String, Integer> columnSizes, Set<String> uniqueColumns) {
        this(schema, name, columns, primaryKey, foreignKeys, columnSizes, uniqueColumns, List.of(), Set.of(),
                Set.of());
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

    /**
     * The composite foreign key a column belongs to, if any.
     *
     * @param column column name (case-insensitive)
     * @return the key and the column's position in it
     */
    public java.util.Optional<Map.Entry<CompositeForeignKey, Integer>> compositeKeyOf(String column) {
        for (CompositeForeignKey k : compositeForeignKeys) {
            for (int i = 0; i < k.columns().size(); i++) {
                if (k.columns().get(i).equalsIgnoreCase(column)) {
                    return java.util.Optional.of(Map.entry(k, i));
                }
            }
        }
        return java.util.Optional.empty();
    }
}
