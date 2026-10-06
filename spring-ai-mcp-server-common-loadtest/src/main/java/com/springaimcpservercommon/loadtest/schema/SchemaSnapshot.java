package com.springaimcpservercommon.loadtest.schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The structure of a database at one moment, as read by {@link SchemaReader}. Holds names, types and constraint
 * definitions only, never row data ({@link Table#rowCount()} is a number). {@link DdlWriter} renders it as DDL.
 *
 * @param product          database product name, e.g. {@code PostgreSQL}
 * @param productVersion   database product version
 * @param schema           the schema the snapshot was narrowed to, or {@code null} for every non-system schema
 * @param capturedAt       when the snapshot was read (UTC)
 * @param identifierQuote  the driver's identifier quote string ({@code "} for PostgreSQL)
 * @param upperCaseNames   whether unquoted identifiers fold to upper case (Oracle, H2) instead of lower case
 * @param extensions       installed extensions (PostgreSQL), prerequisites of the objects below
 * @param schemas          schemas holding the objects below
 * @param enums            enum types (PostgreSQL)
 * @param sequences        sequences not implied by an identity column
 * @param tables           tables and partitions (parents before their partitions), then views and materialized
 *                         views (dependencies first): the order {@link DdlWriter} creates them in
 * @param notes            what could not be read exactly (rendered as comments)
 */
public record SchemaSnapshot(String product, String productVersion, @Nullable String schema, Instant capturedAt,
                             String identifierQuote, boolean upperCaseNames, List<Extension> extensions,
                             List<String> schemas, List<EnumType> enums, List<Sequence> sequences,
                             List<Table> tables, List<String> notes) {

    /** Compact constructor: unmodifiable copies. */
    public SchemaSnapshot {
        extensions = List.copyOf(extensions);
        schemas = List.copyOf(schemas);
        enums = List.copyOf(enums);
        sequences = List.copyOf(sequences);
        tables = List.copyOf(tables);
        notes = List.copyOf(notes);
    }

    /**
     * Identifier quoting for this database.
     *
     * @return quoting rules
     */
    public SqlNames names() {
        return new SqlNames(identifierQuote, upperCaseNames);
    }

    /**
     * Whether the snapshot came from PostgreSQL (exact catalog definitions).
     *
     * @return {@code true} for PostgreSQL
     */
    public boolean postgres() {
        return product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
    }

    /**
     * Number of tables of the given kinds.
     *
     * @param kinds kinds to count
     * @return count
     */
    public long count(Kind... kinds) {
        List<Kind> wanted = List.of(kinds);
        return tables.stream().filter(t -> wanted.contains(t.kind())).count();
    }

    /**
     * A schema-qualified object name.
     *
     * @param schema schema, or {@code null} when the database has none (or it is unknown)
     * @param name   object name
     */
    public record Name(@Nullable String schema, String name) {
    }

    /** What a relation is. */
    public enum Kind {
        /** Ordinary table (also a partition when {@link Table#partitionOf()} is set). */
        TABLE,
        /** Partitioned parent table ({@code PARTITION BY}). */
        PARTITIONED_TABLE,
        /** View. */
        VIEW,
        /** Materialized view. */
        MATERIALIZED_VIEW
    }

    /**
     * A table, partition, view or materialized view.
     *
     * @param schema         schema, or {@code null}
     * @param name           name
     * @param kind           what it is
     * @param columns        columns in ordinal order (a partition repeats its parent's; DDL omits them)
     * @param constraints    primary key, unique, check, exclusion and foreign-key constraints
     * @param indexes        indexes not backing a constraint
     * @param partitionKey   {@code RANGE (created_at)} for a partitioned table, else {@code null}
     * @param partitionOf    the parent of a partition, else {@code null}
     * @param partitionBound {@code FOR VALUES …} or {@code DEFAULT} for a partition, else {@code null}
     * @param viewDefinition the query of a view, or {@code null} when not a view or not readable
     * @param comment        table comment, or {@code null}
     * @param rowCount       exact row count when requested, else {@code null}
     */
    public record Table(@Nullable String schema, String name, Kind kind, List<Column> columns,
                        List<Constraint> constraints, List<Index> indexes, @Nullable String partitionKey,
                        @Nullable Name partitionOf, @Nullable String partitionBound, @Nullable String viewDefinition,
                        @Nullable String comment, @Nullable Long rowCount) {

        /** Compact constructor: unmodifiable copies. */
        public Table {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
            indexes = List.copyOf(indexes);
        }

        /**
         * The table's qualified name.
         *
         * @return name
         */
        public Name qualifiedName() {
            return new Name(schema, name);
        }

        /**
         * Whether this is a view or materialized view.
         *
         * @return {@code true} for views
         */
        public boolean view() {
            return kind == Kind.VIEW || kind == Kind.MATERIALIZED_VIEW;
        }

        /**
         * The same table with a row count.
         *
         * @param rows exact count, or {@code null}
         * @return a copy
         */
        public Table withRowCount(@Nullable Long rows) {
            return new Table(schema, name, kind, columns, constraints, indexes, partitionKey, partitionOf,
                    partitionBound, viewDefinition, comment, rows);
        }
    }

    /** How a column's value is generated by the database. */
    public enum Identity {
        /** {@code GENERATED ALWAYS AS IDENTITY}. */
        ALWAYS,
        /** {@code GENERATED BY DEFAULT AS IDENTITY}. */
        BY_DEFAULT,
        /** An auto-increment column reported by JDBC metadata (rendered in the product's own syntax). */
        AUTO_INCREMENT
    }

    /**
     * A column.
     *
     * @param name         column name
     * @param type         type as the database declares it, e.g. {@code numeric(10,2)}, {@code varchar(30)}
     * @param nullable     whether {@code NULL} is allowed
     * @param defaultValue default expression, or {@code null}
     * @param identity     identity / auto-increment, or {@code null}
     * @param generated    expression of a generated column, or {@code null}
     * @param virtual      whether the generated column is computed on read ({@code VIRTUAL}) rather than stored
     * @param comment      column comment, or {@code null}
     */
    public record Column(String name, String type, boolean nullable, @Nullable String defaultValue,
                         @Nullable Identity identity, @Nullable String generated, boolean virtual,
                         @Nullable String comment) {
    }

    /** Kind of table constraint, in the order they are declared. */
    public enum ConstraintType {
        /** {@code PRIMARY KEY}. */
        PRIMARY_KEY,
        /** {@code UNIQUE}. */
        UNIQUE,
        /** {@code CHECK}. */
        CHECK,
        /** {@code EXCLUDE} (PostgreSQL). */
        EXCLUDE,
        /** {@code FOREIGN KEY} (added after every table exists, so cycles replay). */
        FOREIGN_KEY
    }

    /**
     * A table constraint.
     *
     * @param name       constraint name, or {@code null} when the database does not report one
     * @param type       kind
     * @param columns    constrained columns in key order (empty for expression checks)
     * @param definition the SQL after {@code CONSTRAINT <name>}, e.g. {@code FOREIGN KEY (a) REFERENCES s.t(id)}
     * @param references referenced table of a foreign key, else {@code null}
     */
    public record Constraint(@Nullable String name, ConstraintType type, List<String> columns, String definition,
                             @Nullable Name references) {

        /** Compact constructor: unmodifiable copy. */
        public Constraint {
            columns = List.copyOf(columns);
        }
    }

    /**
     * An index that does not back a constraint.
     *
     * @param name       index name
     * @param unique     whether it is unique
     * @param columns    indexed columns (expression parts omitted)
     * @param definition the complete {@code CREATE [UNIQUE] INDEX …} statement without the semicolon
     */
    public record Index(String name, boolean unique, List<String> columns, String definition) {

        /** Compact constructor: unmodifiable copy. */
        public Index {
            columns = List.copyOf(columns);
        }
    }

    /**
     * A sequence not implied by an identity column.
     *
     * @param schema      schema
     * @param name        name
     * @param dataType    {@code bigint}, {@code integer} or {@code smallint}
     * @param start       start value
     * @param increment   increment
     * @param min         minimum value
     * @param max         maximum value
     * @param cache       cache size
     * @param cycle       whether it wraps around
     * @param ownedBy     the table owning it ({@code serial} columns), or {@code null}
     * @param ownedColumn the owning column, or {@code null}
     */
    public record Sequence(@Nullable String schema, String name, String dataType, long start, long increment,
                           long min, long max, long cache, boolean cycle, @Nullable Name ownedBy,
                           @Nullable String ownedColumn) {
    }

    /**
     * An enum type.
     *
     * @param schema schema
     * @param name   type name
     * @param labels labels in sort order
     */
    public record EnumType(@Nullable String schema, String name, List<String> labels) {

        /** Compact constructor: unmodifiable copy. */
        public EnumType {
            labels = List.copyOf(labels);
        }
    }

    /**
     * An installed extension.
     *
     * @param name    extension name
     * @param schema  schema its objects live in
     * @param version installed version
     */
    public record Extension(String name, String schema, String version) {
    }
}
