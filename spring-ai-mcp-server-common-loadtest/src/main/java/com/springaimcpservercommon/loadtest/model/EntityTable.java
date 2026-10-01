package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A JPA entity of the project and the table it maps to (from the source scan).
 *
 * @param entityName      simple class name, e.g. {@code Customer}
 * @param schema          database schema from {@code @Table(schema=…)}, if any
 * @param table           table name ({@code @Table(name=…)} or Spring Boot's snake-case default)
 * @param idField         Java field annotated {@code @Id}, if found
 * @param idColumn        column of the id field, if found
 * @param fieldColumns    Java field name → column name, for basic attributes
 * @param fieldReferences Java field name → referenced entity simple name, for {@code @ManyToOne}/{@code @OneToOne}
 * @param joinColumns     Java field name → join column, for the references
 * @param sensitiveFields fields that must never be sampled (classification or name based)
 */
public record EntityTable(String entityName, @Nullable String schema, String table, @Nullable String idField,
                          @Nullable String idColumn, Map<String, String> fieldColumns,
                          Map<String, String> fieldReferences, Map<String, String> joinColumns,
                          Set<String> sensitiveFields) {

    /** Compact constructor: ordered, unmodifiable copies. */
    public EntityTable {
        fieldColumns = Collections.unmodifiableMap(new LinkedHashMap<>(fieldColumns));
        fieldReferences = Collections.unmodifiableMap(new LinkedHashMap<>(fieldReferences));
        joinColumns = Collections.unmodifiableMap(new LinkedHashMap<>(joinColumns));
        sensitiveFields = Set.copyOf(sensitiveFields);
    }

    /**
     * Qualified table name, {@code schema.table} or {@code table}.
     *
     * @return qualified name
     */
    public String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
