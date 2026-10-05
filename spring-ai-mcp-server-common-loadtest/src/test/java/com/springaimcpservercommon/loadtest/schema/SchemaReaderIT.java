package com.springaimcpservercommon.loadtest.schema;

import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Kind;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the structure of a real PostgreSQL schema and proves the DDL rebuilds it: the script is replayed into a
 * copy schema, read back, and must render identically. Uses Testcontainers; set {@code LOADTEST_IT_JDBC_URL}
 * (+ {@code LOADTEST_IT_USER}/{@code LOADTEST_IT_PASSWORD}) to run against an existing database instead.
 */
class SchemaReaderIT {

    private static final String SCHEMA = "loadtest_ddl_it";
    private static final String COPY = "loadtest_ddl_it_copy";

    private static PostgreSQLContainer container;
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void database() throws SQLException {
        url = System.getenv("LOADTEST_IT_JDBC_URL");
        if (url == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } else {
            user = System.getenv("LOADTEST_IT_USER");
            password = System.getenv("LOADTEST_IT_PASSWORD");
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            drop(st);
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TYPE " + SCHEMA + ".mood AS ENUM ('happy', 'it''s fine', 'sad')");
            st.execute("CREATE SEQUENCE " + SCHEMA + ".ticket_seq AS integer START 100 INCREMENT 5 CYCLE");
            st.execute("CREATE TABLE " + SCHEMA + ".customers ("
                    + "id bigserial PRIMARY KEY, "
                    + "email text NOT NULL UNIQUE, "
                    + "mood " + SCHEMA + ".mood DEFAULT 'happy', "
                    + "total numeric(10,2) NOT NULL DEFAULT 0 CONSTRAINT total_not_negative CHECK (total >= 0), "
                    + "\"Order\" integer GENERATED ALWAYS AS IDENTITY, "
                    + "double_total numeric GENERATED ALWAYS AS (total * 2) STORED, "
                    + "nickname varchar(30), "
                    + "ticket integer DEFAULT nextval('" + SCHEMA + ".ticket_seq'))");
            st.execute("COMMENT ON TABLE " + SCHEMA + ".customers IS 'People who buy; it''s the root'");
            st.execute("COMMENT ON COLUMN " + SCHEMA + ".customers.email IS 'login name'");
            st.execute("CREATE TABLE " + SCHEMA + ".orders (id bigserial PRIMARY KEY, "
                    + "customer_id bigint NOT NULL REFERENCES " + SCHEMA + ".customers(id) ON DELETE CASCADE, "
                    + "parent_id bigint REFERENCES " + SCHEMA + ".orders(id))");
            st.execute("CREATE INDEX orders_customer_idx ON " + SCHEMA + ".orders (customer_id)");
            st.execute("CREATE UNIQUE INDEX customers_lower_email ON " + SCHEMA + ".customers (lower(email))");
            st.execute("CREATE TABLE " + SCHEMA + ".events (id bigint NOT NULL, at date NOT NULL, kind text, "
                    + "PRIMARY KEY (id, at)) PARTITION BY RANGE (at)");
            st.execute("CREATE TABLE " + SCHEMA + ".events_2026 PARTITION OF " + SCHEMA
                    + ".events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')");
            st.execute("CREATE TABLE " + SCHEMA + ".events_other PARTITION OF " + SCHEMA + ".events DEFAULT");
            st.execute("CREATE INDEX events_at ON " + SCHEMA + ".events (at)");
            st.execute("CREATE VIEW " + SCHEMA + ".v_big_orders AS SELECT o.id, o.customer_id FROM " + SCHEMA
                    + ".orders o WHERE o.id > 1");
            // defined after the view it reads, named so alphabetical order alone would put it first
            st.execute("CREATE VIEW " + SCHEMA + ".a_view_of_view AS SELECT id FROM " + SCHEMA + ".v_big_orders");
            st.execute("CREATE MATERIALIZED VIEW " + SCHEMA + ".order_totals AS SELECT customer_id, count(*) AS n FROM "
                    + SCHEMA + ".orders GROUP BY customer_id");
            st.execute("CREATE UNIQUE INDEX order_totals_customer ON " + SCHEMA + ".order_totals (customer_id)");
            st.execute("CREATE TABLE " + SCHEMA + ".flyway_schema_history (installed_rank int PRIMARY KEY)");
            st.execute("INSERT INTO " + SCHEMA + ".customers(email) SELECT 'c' || g || '@example.com' "
                    + "FROM generate_series(1, 7) g");
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            drop(st);
        }
        if (container != null) {
            container.stop();
        }
    }

    private static void drop(Statement st) throws SQLException {
        st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        st.execute("DROP SCHEMA IF EXISTS " + COPY + " CASCADE");
    }

    private static SchemaSnapshot read(SchemaReader.Options options) throws SQLException {
        return SchemaReader.read(url, user, password, options);
    }

    private static Table table(SchemaSnapshot s, String name) {
        return s.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void readsTheExactDefinitionsFromTheCatalogs() throws SQLException {
        SchemaSnapshot s = read(SchemaReader.Options.schema(SCHEMA));

        assertThat(s.postgres()).isTrue();
        assertThat(s.schemas()).containsExactly(SCHEMA);
        assertThat(s.tables()).extracting(Table::name).doesNotContain("flyway_schema_history");
        assertThat(s.enums()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("mood");
            assertThat(e.labels()).containsExactly("happy", "it's fine", "sad");
        });

        Table customers = table(s, "customers");
        assertThat(customers.comment()).isEqualTo("People who buy; it's the root");
        assertThat(customers.columns()).extracting(SchemaSnapshot.Column::name)
                .containsExactly("id", "email", "mood", "total", "Order", "double_total", "nickname", "ticket");
        assertThat(customers.columns().get(3).type()).isEqualTo("numeric(10,2)");
        assertThat(customers.columns().get(3).nullable()).isFalse();
        assertThat(customers.columns().get(4).identity()).isEqualTo(SchemaSnapshot.Identity.ALWAYS);
        assertThat(customers.columns().get(5).generated()).contains("total");
        assertThat(customers.columns().get(6).type()).isEqualTo("character varying(30)");
        assertThat(customers.columns().get(1).comment()).isEqualTo("login name");
        // search_path is pg_catalog while reading, so every reference is schema-qualified
        assertThat(customers.columns().get(2).type()).isEqualTo(SCHEMA + ".mood");
        assertThat(customers.columns().get(7).defaultValue()).contains(SCHEMA + ".ticket_seq");
        assertThat(customers.constraints()).extracting(SchemaSnapshot.Constraint::type)
                .containsExactlyInAnyOrder(SchemaSnapshot.ConstraintType.PRIMARY_KEY,
                        SchemaSnapshot.ConstraintType.UNIQUE, SchemaSnapshot.ConstraintType.CHECK);
        assertThat(customers.constraints()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("total_not_negative");
            assertThat(c.definition()).contains("CHECK (total >= 0");
        });
        // the expression index is an index; the unique constraint's index is not listed twice
        assertThat(customers.indexes()).extracting(SchemaSnapshot.Index::name).containsExactly("customers_lower_email");

        Table orders = table(s, "orders");
        assertThat(orders.constraints()).filteredOn(c -> c.type() == SchemaSnapshot.ConstraintType.FOREIGN_KEY)
                .extracting(SchemaSnapshot.Constraint::columns)
                .containsExactlyInAnyOrder(java.util.List.of("customer_id"), java.util.List.of("parent_id"));
        assertThat(orders.constraints()).anySatisfy(c -> assertThat(c.definition())
                .contains("REFERENCES " + SCHEMA + ".customers(id) ON DELETE CASCADE"));

        Table events = table(s, "events");
        assertThat(events.kind()).isEqualTo(Kind.PARTITIONED_TABLE);
        assertThat(events.partitionKey()).isEqualTo("RANGE (at)");
        assertThat(table(s, "events_2026").partitionOf()).isEqualTo(new SchemaSnapshot.Name(SCHEMA, "events"));
        assertThat(table(s, "events_2026").partitionBound()).contains("2026-01-01");
        assertThat(table(s, "events_other").partitionBound()).isEqualTo("DEFAULT");
        // the partitions' own copies of the parent's index are not listed
        assertThat(table(s, "events_2026").indexes()).isEmpty();

        assertThat(s.sequences()).extracting(SchemaSnapshot.Sequence::name).contains("ticket_seq", "customers_id_seq")
                .doesNotContain("customers_Order_seq");
        SchemaSnapshot.Sequence ticket = s.sequences().stream().filter(q -> q.name().equals("ticket_seq"))
                .findFirst().orElseThrow();
        assertThat(ticket.dataType()).isEqualTo("integer");
        assertThat(ticket.start()).isEqualTo(100);
        assertThat(ticket.increment()).isEqualTo(5);
        assertThat(ticket.cycle()).isTrue();
        assertThat(s.sequences().stream().filter(q -> q.name().equals("customers_id_seq")).findFirst().orElseThrow()
                .ownedColumn()).isEqualTo("id");
    }

    @Test
    void ordersPartitionsAfterParentsAndViewsAfterTheirDependencies() throws SQLException {
        SchemaSnapshot s = read(SchemaReader.Options.schema(SCHEMA));

        var names = s.tables().stream().map(Table::name).toList();
        assertThat(names.indexOf("events")).isLessThan(names.indexOf("events_2026"));
        assertThat(names.indexOf("customers")).isLessThan(names.indexOf("v_big_orders"));
        assertThat(names.indexOf("v_big_orders")).isLessThan(names.indexOf("a_view_of_view"));
        assertThat(names.indexOf("events_other")).isLessThan(names.indexOf("v_big_orders")); // tables, then views
        assertThat(table(s, "order_totals").kind()).isEqualTo(Kind.MATERIALIZED_VIEW);
        assertThat(table(s, "order_totals").indexes()).extracting(SchemaSnapshot.Index::name)
                .containsExactly("order_totals_customer");
        assertThat(table(s, "v_big_orders").viewDefinition()).contains("WHERE id > 1").doesNotEndWith(";");
    }

    @Test
    void theDdlRebuildsTheSameStructure() throws SQLException {
        String ddl = DdlWriter.render(read(SchemaReader.Options.schema(SCHEMA)));
        assertThat(ddl).contains("CREATE TABLE " + SCHEMA + ".customers (")
                .contains("PARTITION OF " + SCHEMA + ".events FOR VALUES FROM")
                .contains("CREATE MATERIALIZED VIEW " + SCHEMA + ".order_totals AS")
                .doesNotContain("flyway_schema_history");

        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute(ddl.replace(SCHEMA, COPY));
        }
        String replayed = DdlWriter.render(read(SchemaReader.Options.schema(COPY))).replace(COPY, SCHEMA);

        // identical but for the capture time in the header
        assertThat(body(replayed)).isEqualTo(body(ddl));
    }

    private static String body(String ddl) {
        return ddl.lines().filter(l -> !l.startsWith("-- captured")).collect(Collectors.joining("\n"));
    }

    @Test
    void anotherDriverPathReadsTablesColumnsKeysAndIndexes() throws SQLException {
        SchemaSnapshot s = read(new SchemaReader.Options(SCHEMA, false, true));

        assertThat(s.notes()).anyMatch(n -> n.contains("JDBC metadata"));
        assertThat(s.enums()).isEmpty();
        Table customers = table(s, "customers");
        assertThat(customers.columns()).extracting(SchemaSnapshot.Column::name).contains("id", "email", "nickname");
        assertThat(customers.columns().stream().filter(c -> c.name().equals("nickname")).findFirst().orElseThrow()
                .type()).isEqualTo("varchar(30)");
        assertThat(customers.columns().stream().filter(c -> c.name().equals("total")).findFirst().orElseThrow()
                .type()).isEqualTo("numeric(10,2)");
        assertThat(customers.constraints()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(SchemaSnapshot.ConstraintType.PRIMARY_KEY);
            assertThat(c.columns()).containsExactly("id");
        });
        Table orders = table(s, "orders");
        assertThat(orders.constraints()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(SchemaSnapshot.ConstraintType.FOREIGN_KEY);
            assertThat(c.definition()).contains("REFERENCES " + SCHEMA + ".customers (id)").contains("ON DELETE CASCADE");
        });
        assertThat(orders.indexes()).extracting(SchemaSnapshot.Index::name).contains("orders_customer_idx");
        assertThat(DdlWriter.render(s)).contains("-- view " + SCHEMA + ".v_big_orders: query not available");
    }

    @Test
    void countsRowsOnRequestAndNeverReadsRowValues() throws SQLException {
        SchemaSnapshot without = read(SchemaReader.Options.schema(SCHEMA));
        assertThat(without.tables()).allSatisfy(t -> assertThat(t.rowCount()).isNull());

        SchemaSnapshot with = read(SchemaReader.Options.schema(SCHEMA).withRowCounts(true));
        assertThat(table(with, "customers").rowCount()).isEqualTo(7);
        assertThat(table(with, "orders").rowCount()).isZero();
        assertThat(table(with, "v_big_orders").rowCount()).isNull();
        assertThat(DdlWriter.render(with)).contains("-- rows: 7").doesNotContain("@example.com");
    }

    @Test
    void leavesTheConnectionAsItFoundIt() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            String before;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW search_path")) {
                rs.next();
                before = rs.getString(1);
            }
            SchemaSnapshot s = SchemaReader.read(c, SchemaReader.Options.schema(SCHEMA));
            assertThat(s.tables()).isNotEmpty();
            assertThat(c.isReadOnly()).isFalse();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW search_path")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(before);
            }
            assertThat(c.isClosed()).isFalse();
        }
    }

    @Test
    void anUnknownSchemaIsAnEmptySnapshotWithANote() throws SQLException {
        SchemaSnapshot s = read(SchemaReader.Options.schema("no_such_schema"));

        assertThat(s.tables()).isEmpty();
        assertThat(s.notes()).anyMatch(n -> n.contains("no_such_schema"));
        assertThat(DdlWriter.render(s)).contains("-- note:").doesNotContain("CREATE TABLE");
    }

    @Test
    void theLibrarysOwnSchemaAndSystemSchemasAreNeverIncluded() throws SQLException {
        SchemaSnapshot s = read(SchemaReader.Options.defaults());

        assertThat(s.schemas()).contains(SCHEMA).doesNotContain("pg_catalog", "information_schema", "dynamic_ai");
        assertThat(s.tables()).noneMatch(t -> "pg_catalog".equals(t.schema()) || "information_schema".equals(t.schema()));
    }
}
