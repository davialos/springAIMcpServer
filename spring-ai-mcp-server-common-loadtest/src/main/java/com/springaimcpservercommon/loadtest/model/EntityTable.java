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
 * @param columnLengths   Java field → {@code @Column(length)} (255 when {@code @Column} gives none), strings only
 * @param uniqueFields    fields mapped to {@code @Column(unique = true)} (generated values must not repeat)
 * @param idGenerated     whether the id is {@code @GeneratedValue} (the server assigns it on create)
 */
public record EntityTable(String entityName, @Nullable String schema, String table, @Nullable String idField,
                          @Nullable String idColumn, Map<String, String> fieldColumns,
                          Map<String, String> fieldReferences, Map<String, String> joinColumns,
                          Set<String> sensitiveFields, Map<String, Integer> columnLengths, Set<String> uniqueFields,
                          boolean idGenerated) {

    /** Compact constructor: ordered, unmodifiable copies. */
    public EntityTable {
        fieldColumns = Collections.unmodifiableMap(new LinkedHashMap<>(fieldColumns));
        fieldReferences = Collections.unmodifiableMap(new LinkedHashMap<>(fieldReferences));
        joinColumns = Collections.unmodifiableMap(new LinkedHashMap<>(joinColumns));
        sensitiveFields = Set.copyOf(sensitiveFields);
        columnLengths = Collections.unmodifiableMap(new LinkedHashMap<>(columnLengths));
        uniqueFields = Set.copyOf(uniqueFields);
    }

    /**
     * An entity without column facts (lengths, uniqueness) and with a server-generated id.
     *
     * @param entityName      simple class name
     * @param schema          database schema, or {@code null}
     * @param table           table name
     * @param idField         id field, or {@code null}
     * @param idColumn        id column, or {@code null}
     * @param fieldColumns    field → column
     * @param fieldReferences field → referenced entity
     * @param joinColumns     field → join column
     * @param sensitiveFields sensitive fields
     */
    public EntityTable(String entityName, @Nullable String schema, String table, @Nullable String idField,
                       @Nullable String idColumn, Map<String, String> fieldColumns,
                       Map<String, String> fieldReferences, Map<String, String> joinColumns,
                       Set<String> sensitiveFields) {
        this(entityName, schema, table, idField, idColumn, fieldColumns, fieldReferences, joinColumns,
                sensitiveFields, Map.of(), Set.of(), true);
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
