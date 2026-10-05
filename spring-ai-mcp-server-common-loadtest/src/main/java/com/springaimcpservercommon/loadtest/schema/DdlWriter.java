package com.springaimcpservercommon.loadtest.schema;

import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Column;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Constraint;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.ConstraintType;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Kind;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link SchemaSnapshot} as DDL that rebuilds the structure on an empty database.
 * <p>
 * Statements are ordered so the script replays without errors: schemas, extensions, enum types, sequences, tables
 * (parents before partitions), views (dependencies first), indexes, foreign keys (after every table exists, so
 * reference cycles replay), sequence ownership, comments. Names come from the database and are quoted by
 * {@link SqlNames}; string literals double their quotes and comments cannot span lines, so nothing read from the
 * database can add a statement to the script.
 */
public final class DdlWriter {

    private DdlWriter() {
    }

    /**
     * The DDL of a snapshot, with a header comment naming the database, schema, capture time and any limits.
     *
     * @param snapshot what {@link SchemaReader} found
     * @return a SQL script ending in a newline
     */
    public static String render(SchemaSnapshot snapshot) {
        return new Renderer(snapshot).render();
    }

    private static final class Renderer {
        private final SchemaSnapshot s;
        private final SqlNames names;
        private final StringBuilder out = new StringBuilder();

        Renderer(SchemaSnapshot s) {
            this.s = s;
            this.names = s.names();
        }

        String render() {
            header();
            schemas();
            extensions();
            enums();
            sequences();
            List<Table> tables = s.tables().stream().filter(t -> !t.view()).toList();
            List<Table> views = s.tables().stream().filter(Table::view).toList();
            tables.forEach(this::createTable);
            views.forEach(this::createView);
            s.tables().forEach(this::indexes);
            s.tables().forEach(this::foreignKeys);
            sequenceOwnership();
            comments();
            return out.toString();
        }

        private void header() {
            line("-- Database schema snapshot (structure only, no row data)");
            line("-- database:  " + flat(s.product() + " " + s.productVersion()));
            line("-- schema:    " + (s.schema() == null ? "all non-system schemas" : flat(s.schema())));
            line("-- captured:  " + s.capturedAt());
            line("-- contents:  " + s.count(Kind.TABLE, Kind.PARTITIONED_TABLE) + " tables, "
                    + s.count(Kind.VIEW, Kind.MATERIALIZED_VIEW) + " views, " + s.sequences().size() + " sequences, "
                    + s.enums().size() + " enum types");
            for (String note : s.notes()) {
                line("-- note:      " + flat(note));
            }
            out.append('\n');
        }

        private void schemas() {
            boolean any = false;
            for (String schema : s.schemas()) {
                if (!schema.equalsIgnoreCase("public")) {
                    line("CREATE SCHEMA IF NOT EXISTS " + names.id(schema) + ";");
                    any = true;
                }
            }
            blank(any);
        }

        private void extensions() {
            for (SchemaSnapshot.Extension e : s.extensions()) {
                line("CREATE EXTENSION IF NOT EXISTS " + names.id(e.name()) + " WITH SCHEMA " + names.id(e.schema())
                        + ";");
            }
            blank(!s.extensions().isEmpty());
        }

        private void enums() {
            for (SchemaSnapshot.EnumType e : s.enums()) {
                line("CREATE TYPE " + names.qualified(e.schema(), e.name()) + " AS ENUM ("
                        + String.join(", ", e.labels().stream().map(Renderer::literal).toList()) + ");");
            }
            blank(!s.enums().isEmpty());
        }

        private void sequences() {
            for (SchemaSnapshot.Sequence q : s.sequences()) {
                line("CREATE SEQUENCE " + names.qualified(q.schema(), q.name()) + " AS " + q.dataType()
                        + " INCREMENT BY " + q.increment() + " MINVALUE " + q.min() + " MAXVALUE " + q.max()
                        + " START WITH " + q.start() + " CACHE " + q.cache() + (q.cycle() ? " CYCLE" : " NO CYCLE")
                        + ";");
            }
            blank(!s.sequences().isEmpty());
        }

        private void createTable(Table t) {
            if (t.rowCount() != null) {
                line("-- rows: " + t.rowCount());
            }
            String name = names.qualified(t.schema(), t.name());
            if (t.partitionOf() != null) {
                String partitionBy = t.partitionKey() == null ? "" : " PARTITION BY " + t.partitionKey();
                line("CREATE TABLE " + name + " PARTITION OF " + names.qualified(t.partitionOf()) + " "
                        + (t.partitionBound() == null ? "DEFAULT" : t.partitionBound()) + partitionBy + ";");
                out.append('\n');
                return;
            }
            List<String> body = new ArrayList<>();
            t.columns().forEach(c -> body.add(column(c)));
            for (Constraint c : t.constraints()) {
                if (c.type() != ConstraintType.FOREIGN_KEY) {
                    body.add(c.name() == null ? c.definition()
                            : "CONSTRAINT " + names.id(c.name()) + " " + c.definition());
                }
            }
            line("CREATE TABLE " + name + " (");
            for (int i = 0; i < body.size(); i++) {
                line("    " + body.get(i) + (i + 1 < body.size() ? "," : ""));
            }
            line(")" + (t.partitionKey() == null ? "" : " PARTITION BY " + t.partitionKey()) + ";");
            out.append('\n');
        }

        private String column(Column c) {
            StringBuilder b = new StringBuilder(names.id(c.name())).append(' ').append(c.type());
            if (c.generated() != null) {
                b.append(" GENERATED ALWAYS AS (").append(c.generated()).append(')')
                        .append(c.virtual() ? " VIRTUAL" : " STORED");
            } else if (c.identity() == SchemaSnapshot.Identity.ALWAYS) {
                b.append(" GENERATED ALWAYS AS IDENTITY");
            } else if (c.identity() == SchemaSnapshot.Identity.BY_DEFAULT) {
                b.append(" GENERATED BY DEFAULT AS IDENTITY");
            } else if (c.identity() == SchemaSnapshot.Identity.AUTO_INCREMENT) {
                String product = s.product().toLowerCase(Locale.ROOT);
                b.append(product.contains("mysql") || product.contains("mariadb") ? " AUTO_INCREMENT"
                        : " GENERATED BY DEFAULT AS IDENTITY");
            } else if (c.defaultValue() != null) {
                b.append(" DEFAULT ").append(c.defaultValue());
            }
            if (!c.nullable()) {
                b.append(" NOT NULL");
            }
            return b.toString();
        }

        private void createView(Table t) {
            String name = names.qualified(t.schema(), t.name());
            if (t.viewDefinition() == null) {
                line("-- view " + flat(name) + ": query not available (JDBC metadata)");
                out.append('\n');
                return;
            }
            boolean materialized = t.kind() == Kind.MATERIALIZED_VIEW;
            line("CREATE " + (materialized ? "MATERIALIZED " : "") + "VIEW " + name + " AS");
            line(t.viewDefinition().stripIndent().strip() + (materialized ? "\nWITH NO DATA;" : ";"));
            out.append('\n');
        }

        private void indexes(Table t) {
            if (t.indexes().isEmpty() || (t.view() && t.viewDefinition() == null)) {
                return;
            }
            for (SchemaSnapshot.Index i : t.indexes()) {
                String definition = i.definition();
                if (t.kind() == Kind.PARTITIONED_TABLE) {
                    // "ON ONLY" would leave an invalid parent index; without it the index cascades to partitions
                    definition = definition.replaceFirst(" ON ONLY ", " ON ");
                }
                line(definition + ";");
            }
            out.append('\n');
        }

        private void foreignKeys(Table t) {
            boolean any = false;
            for (Constraint c : t.constraints()) {
                if (c.type() == ConstraintType.FOREIGN_KEY) {
                    line("ALTER TABLE " + names.qualified(t.schema(), t.name()) + " ADD "
                            + (c.name() == null ? "" : "CONSTRAINT " + names.id(c.name()) + " ") + c.definition() + ";");
                    any = true;
                }
            }
            blank(any);
        }

        private void sequenceOwnership() {
            boolean any = false;
            for (SchemaSnapshot.Sequence q : s.sequences()) {
                if (q.ownedBy() != null && q.ownedColumn() != null) {
                    line("ALTER SEQUENCE " + names.qualified(q.schema(), q.name()) + " OWNED BY "
                            + names.qualified(q.ownedBy()) + "." + names.id(q.ownedColumn()) + ";");
                    any = true;
                }
            }
            blank(any);
        }

        private void comments() {
            boolean any = false;
            for (Table t : s.tables()) {
                String name = names.qualified(t.schema(), t.name());
                if (t.comment() != null) {
                    String what = t.kind() == Kind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW"
                            : t.kind() == Kind.VIEW ? "VIEW" : "TABLE";
                    line("COMMENT ON " + what + " " + name + " IS " + literal(t.comment()) + ";");
                    any = true;
                }
                if (t.partitionOf() != null) {
                    continue; // a partition's columns are its parent's
                }
                for (Column c : t.columns()) {
                    if (c.comment() != null) {
                        line("COMMENT ON COLUMN " + name + "." + names.id(c.name()) + " IS " + literal(c.comment())
                                + ";");
                        any = true;
                    }
                }
            }
            blank(any);
        }

        private void line(String text) {
            out.append(text).append('\n');
        }

        private void blank(boolean after) {
            if (after) {
                out.append('\n');
            }
        }

        private static String literal(String text) {
            return "'" + text.replace("'", "''") + "'";
        }

        /** Text for a {@code --} comment: one line, so it can never start a statement. */
        private static String flat(String text) {
            return text.replaceAll("\\s+", " ").strip();
        }
    }
}
