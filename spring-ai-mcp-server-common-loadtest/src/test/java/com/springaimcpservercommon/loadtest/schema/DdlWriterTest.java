package com.springaimcpservercommon.loadtest.schema;

import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Column;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Constraint;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.ConstraintType;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Identity;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Index;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Kind;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Name;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Table;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlWriterTest {

    private static Column col(String name, String type, boolean nullable) {
        return new Column(name, type, nullable, null, null, null, false, null);
    }

    private static SchemaSnapshot snapshot(List<Table> tables, List<SchemaSnapshot.Sequence> sequences,
                                           List<String> notes) {
        return new SchemaSnapshot("PostgreSQL", "16.1", null, Instant.parse("2026-10-05T10:00:00Z"), "\"", false,
                List.of(), List.of("public", "shop"), List.of(new SchemaSnapshot.EnumType("shop", "mood",
                List.of("happy", "it's fine"))), sequences, tables, notes);
    }

    private static Table table(String name, List<Column> columns, List<Constraint> constraints, List<Index> indexes) {
        return new Table("shop", name, Kind.TABLE, columns, constraints, indexes, null, null, null, null, null, null);
    }

    @Test
    void ordersStatementsSoTheScriptReplays() {
        Constraint pk = new Constraint("customers_pkey", ConstraintType.PRIMARY_KEY, List.of("id"),
                "PRIMARY KEY (id)", null);
        Constraint fk = new Constraint("orders_customer_fk", ConstraintType.FOREIGN_KEY, List.of("customer_id"),
                "FOREIGN KEY (customer_id) REFERENCES shop.customers(id) ON DELETE CASCADE",
                new Name("shop", "customers"));
        Table customers = table("customers", List.of(
                new Column("id", "bigint", false, null, Identity.ALWAYS, null, false, "surrogate key"),
                new Column("status", "text", true, "'new'::text", null, null, false, null),
                new Column("double_id", "bigint", true, null, null, "(id * 2)", false, null)), List.of(pk), List.of());
        Table orders = table("orders", List.of(col("id", "bigint", false), col("customer_id", "bigint", false)),
                List.of(fk), List.of(new Index("orders_customer_idx", false, List.of("customer_id"),
                        "CREATE INDEX orders_customer_idx ON shop.orders USING btree (customer_id)")));
        Table view = new Table("shop", "v_orders", Kind.VIEW, List.of(col("id", "bigint", true)), List.of(), List.of(),
                null, null, null, "SELECT id\n   FROM shop.orders", null, null);
        SchemaSnapshot.Sequence seq = new SchemaSnapshot.Sequence("shop", "ticket_seq", "bigint", 1, 1, 1,
                Long.MAX_VALUE, 1, false, new Name("shop", "orders"), "id");

        String ddl = DdlWriter.render(snapshot(List.of(customers, orders, view), List.of(seq), List.of()));

        assertThat(ddl).contains("CREATE SCHEMA IF NOT EXISTS shop;")
                .doesNotContain("CREATE SCHEMA IF NOT EXISTS public")
                .contains("CREATE TYPE shop.mood AS ENUM ('happy', 'it''s fine');")
                .contains("id bigint GENERATED ALWAYS AS IDENTITY NOT NULL")
                .contains("status text DEFAULT 'new'::text")
                .contains("double_id bigint GENERATED ALWAYS AS ((id * 2)) STORED")
                .contains("CONSTRAINT customers_pkey PRIMARY KEY (id)")
                .contains("CREATE VIEW shop.v_orders AS\nSELECT id\n   FROM shop.orders;")
                .contains("COMMENT ON COLUMN shop.customers.id IS 'surrogate key';");
        // schema, type, sequence, tables, view, index, foreign key, ownership, comments
        assertThat(List.of(ddl.indexOf("CREATE SCHEMA"), ddl.indexOf("CREATE TYPE"), ddl.indexOf("CREATE SEQUENCE"),
                ddl.indexOf("CREATE TABLE shop.customers"), ddl.indexOf("CREATE TABLE shop.orders"),
                ddl.indexOf("CREATE VIEW"), ddl.indexOf("CREATE INDEX"), ddl.indexOf("ADD CONSTRAINT"),
                ddl.indexOf("OWNED BY"), ddl.indexOf("COMMENT ON")))
                .isSorted();
        // the foreign key is not inline: it is added after every table exists
        assertThat(ddl.substring(0, ddl.indexOf("ALTER TABLE"))).doesNotContain("REFERENCES");
        assertThat(ddl).contains("ALTER TABLE shop.orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY");
    }

    @Test
    void namesAndTextFromTheDatabaseCannotAddStatements() {
        Table evil = table("Order\"; DROP TABLE users; --", List.of(col("select", "text", true)), List.of(),
                List.of());
        Table commented = new Table("shop", "t", Kind.TABLE, List.of(col("a", "int", true)), List.of(), List.of(),
                null, null, null, null, "it's a\nnew line'); DROP TABLE x; --", null);

        String ddl = DdlWriter.render(snapshot(List.of(evil, commented), List.of(),
                List.of("note with\nnewline\nDROP TABLE y;")));

        assertThat(ddl).contains("CREATE TABLE shop.\"Order\"\"; DROP TABLE users; --\" (")
                .contains("\"select\" text")
                .contains("COMMENT ON TABLE shop.t IS 'it''s a\nnew line''); DROP TABLE x; --';")
                .contains("-- note:      note with newline DROP TABLE y;");
        // no line of the header can start a statement
        assertThat(ddl.lines().filter(l -> l.startsWith("DROP"))).isEmpty();
    }

    @Test
    void partitionsFollowTheirParentAndIndexesCascade() {
        Table parent = new Table("shop", "events", Kind.PARTITIONED_TABLE, List.of(col("id", "bigint", false),
                col("at", "date", false)), List.of(new Constraint("events_pkey", ConstraintType.PRIMARY_KEY,
                List.of("id", "at"), "PRIMARY KEY (id, at)", null)),
                List.of(new Index("events_at", false, List.of("at"),
                        "CREATE INDEX events_at ON ONLY shop.events USING btree (at)")),
                "RANGE (at)", null, null, null, null, null);
        Table child = new Table("shop", "events_2026", Kind.TABLE, List.of(col("id", "bigint", false),
                col("at", "date", false)), List.of(), List.of(), null, new Name("shop", "events"),
                "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')", null, null, null);

        String ddl = DdlWriter.render(snapshot(List.of(parent, child), List.of(), List.of()));

        assertThat(ddl).contains(") PARTITION BY RANGE (at);")
                .contains("CREATE TABLE shop.events_2026 PARTITION OF shop.events FOR VALUES FROM ('2026-01-01') "
                        + "TO ('2027-01-01');")
                .contains("CREATE INDEX events_at ON shop.events USING btree (at);")
                .doesNotContain("ON ONLY");
        assertThat(ddl.indexOf("CREATE TABLE shop.events (")).isLessThan(ddl.indexOf("PARTITION OF"));
        // the partition repeats no columns
        assertThat(ddl.substring(ddl.indexOf("PARTITION OF"))).doesNotContain("bigint");
    }

    @Test
    void materializedViewsAreCreatedEmptyAndRowCountsAreComments() {
        Table counted = table("customers", List.of(col("id", "bigint", false)), List.of(), List.of()).withRowCount(42L);
        Table matview = new Table("shop", "totals", Kind.MATERIALIZED_VIEW, List.of(col("n", "bigint", true)),
                List.of(), List.of(), null, null, null, "SELECT count(*) AS n\n   FROM shop.customers", null, null);

        String ddl = DdlWriter.render(snapshot(List.of(counted, matview), List.of(), List.of()));

        assertThat(ddl).contains("-- rows: 42\nCREATE TABLE shop.customers (")
                .contains("CREATE MATERIALIZED VIEW shop.totals AS\nSELECT count(*) AS n\n   FROM shop.customers\n"
                        + "WITH NO DATA;");
    }

    @Test
    void viewsWithoutAQueryAreListedAsComments() {
        Table view = new Table("shop", "v", Kind.VIEW, List.of(col("a", "int", true)), List.of(), List.of(), null,
                null, null, null, null, null);

        String ddl = DdlWriter.render(snapshot(List.of(view), List.of(), List.of()));

        assertThat(ddl).contains("-- view shop.v: query not available").doesNotContain("CREATE VIEW");
    }

    @Test
    void quotesOnlyWhatNeedsQuoting() {
        SqlNames lower = new SqlNames("\"", false);
        SqlNames upper = new SqlNames("\"", true);
        assertThat(lower.id("customers")).isEqualTo("customers");
        assertThat(lower.id("Customers")).isEqualTo("\"Customers\"");
        assertThat(lower.id("order")).isEqualTo("\"order\"");
        assertThat(lower.id("a b")).isEqualTo("\"a b\"");
        assertThat(lower.id("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(upper.id("CUSTOMERS")).isEqualTo("CUSTOMERS");
        assertThat(upper.id("customers")).isEqualTo("\"customers\"");
        assertThat(new SqlNames("", false).id("anything goes")).isEqualTo("anything goes");
        assertThat(lower.qualified(null, "t")).isEqualTo("t");
        assertThat(lower.qualified("s", "T")).isEqualTo("s.\"T\"");
    }
}
