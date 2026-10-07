package com.springaimcpservercommon.loadtest.data;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BulkLoader} and {@link DatabaseSnapshot} on PostgreSQL (the one database that has both): realistic volume
 * with every constraint kept, and a snapshot that makes runs start from the same rows.
 */
class BulkLoadAndSnapshotIT {

    private static PostgreSQLContainer container;
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void start() {
        container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        url = container.getJdbcUrl();
        user = container.getUsername();
        password = container.getPassword();
    }

    @AfterAll
    static void stop() {
        container.stop();
    }

    @BeforeEach
    void schema() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS app CASCADE");
            st.execute("DROP SCHEMA IF EXISTS loadtest_snapshot_baseline CASCADE");
            st.execute("CREATE SCHEMA app");
            st.execute("CREATE TABLE app.customers (id bigserial PRIMARY KEY, email varchar(30) NOT NULL UNIQUE,"
                    + " first_name varchar(20) NOT NULL, created_at timestamp NOT NULL DEFAULT now(),"
                    + " active boolean NOT NULL DEFAULT true)");
            st.execute("CREATE TABLE app.orders (id bigserial PRIMARY KEY, customer_id bigint NOT NULL REFERENCES"
                    + " app.customers, total numeric(10,2) NOT NULL, placed date NOT NULL)");
            st.execute("CREATE TABLE app.order_lines (order_id bigint NOT NULL REFERENCES app.orders, line_no int NOT NULL,"
                    + " sku varchar(12) NOT NULL, PRIMARY KEY (order_id, line_no))");
            st.execute("CREATE TABLE app.shipment_items (id serial PRIMARY KEY, order_id bigint NOT NULL, line_no int NOT NULL,"
                    + " FOREIGN KEY (order_id, line_no) REFERENCES app.order_lines (order_id, line_no))");
            st.execute("CREATE TABLE app.product_tags (product_id int NOT NULL, tag_id int NOT NULL,"
                    + " PRIMARY KEY (product_id, tag_id))");
            st.execute("INSERT INTO app.customers (email, first_name) VALUES ('seed@example.com', 'Seed')");
        }
    }

    private static long count(String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM app." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long query(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static BulkLoader loader(List<String> log) throws SQLException {
        return BulkLoader.connect(url, user, password, "app", log::add).batchSize(250);
    }

    @Test
    void loadsParentsFirstKeepingEveryConstraint() throws SQLException {
        List<String> log = new ArrayList<>();
        Map<String, Long> rows = new LinkedHashMap<>(); // children listed first: the loader orders them
        rows.put("shipment_items", 500L);
        rows.put("order_lines", 3000L);
        rows.put("orders", 1000L);
        rows.put("customers", 700L);
        try (BulkLoader loader = loader(log)) {
            BulkLoader.Result r = loader.load(rows);
            assertThat(r.inserted()).containsEntry("customers", 700L).containsEntry("orders", 1000L)
                    .containsEntry("order_lines", 3000L).containsEntry("shipment_items", 500L);
            assertThat(r.inserted().keySet()).containsExactly("customers", "orders", "order_lines", "shipment_items");
        }
        assertThat(count("customers")).isEqualTo(701);
        assertThat(count("order_lines")).isEqualTo(3000);
        // unique emails, within the column length, and the seed row untouched
        assertThat(query("SELECT count(DISTINCT email) FROM app.customers")).isEqualTo(701);
        assertThat(query("SELECT max(length(email)) FROM app.customers")).isLessThanOrEqualTo(30);
        // foreign keys point at existing parents (the database enforced them), composite pairs exist together
        assertThat(query("SELECT count(*) FROM app.shipment_items s JOIN app.order_lines l"
                + " ON l.order_id = s.order_id AND l.line_no = s.line_no")).isEqualTo(500);
        // identity values were left to the database; defaults applied
        assertThat(query("SELECT count(*) FROM app.customers WHERE active")).isEqualTo(701);
        assertThat(query("SELECT max(id) FROM app.customers")).isEqualTo(701);
        assertThat(log).anyMatch(l -> l.equals("bulk: orders +1000 rows"));
        assertThat(String.join("\n", log)).doesNotContain("@example.com");
    }

    @Test
    void sameSeedSameData() throws SQLException {
        try (BulkLoader loader = loader(new ArrayList<>())) {
            loader.seed(7).load(Map.of("customers", 50L));
        }
        String first = text("SELECT string_agg(first_name || email, ',' ORDER BY id) FROM app.customers");
        schema();
        try (BulkLoader loader = loader(new ArrayList<>())) {
            loader.seed(7).load(Map.of("customers", 50L));
        }
        assertThat(text("SELECT string_agg(first_name || email, ',' ORDER BY id) FROM app.customers")).isEqualTo(first);
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void joinTablesGetDistinctCombinationsAndStopWhenTheyRunOut() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE app.tags (id int PRIMARY KEY)");
            st.execute("CREATE TABLE app.products (id int PRIMARY KEY)");
            st.execute("INSERT INTO app.tags SELECT g FROM generate_series(1, 3) g");
            st.execute("INSERT INTO app.products SELECT g FROM generate_series(1, 2) g");
            st.execute("ALTER TABLE app.product_tags ADD FOREIGN KEY (product_id) REFERENCES app.products");
            st.execute("ALTER TABLE app.product_tags ADD FOREIGN KEY (tag_id) REFERENCES app.tags");
        }
        List<String> log = new ArrayList<>();
        try (BulkLoader loader = loader(log)) {
            BulkLoader.Result r = loader.load(Map.of("product_tags", 100L));
            assertThat(r.inserted().get("product_tags")).isEqualTo(6); // 2 products x 3 tags, no duplicates
        }
        assertThat(count("product_tags")).isEqualTo(6);
        assertThat(log).anyMatch(l -> l.contains("ran out of distinct parent combinations"));
    }

    @Test
    void refusesWhatItCannotDoSafely() throws SQLException {
        try (BulkLoader loader = loader(new ArrayList<>())) {
            assertThatThrownBy(() -> loader.load(Map.of("nope", 1L))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown table");
            // a child whose parent table is empty
            assertThatThrownBy(() -> loader.load(Map.of("order_lines", 5L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no rows");
            assertThat(count("order_lines")).isZero();
        }
        try (BulkLoader prod = BulkLoader.connect(url + "&ApplicationName=prod-orders-db", user, password, "app",
                s -> { })) {
            assertThatThrownBy(() -> prod.load(Map.of("customers", 1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("looks like production");
            assertThat(count("customers")).isEqualTo(1);
        }
        assertThat(BulkLoader.PRODUCTION.matcher("jdbc:postgresql://prod-db:5432/x").find()).isTrue();
        assertThat(BulkLoader.PRODUCTION.matcher("jdbc:postgresql://localhost/productcatalog").find()).isFalse();
    }

    @Test
    void aFailingBatchRollsBackAndSaysWhere() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE app.customers ADD CONSTRAINT short_name CHECK (length(first_name) < 4) NOT VALID");
        }
        try (BulkLoader loader = loader(new ArrayList<>())) {
            assertThatThrownBy(() -> loader.load(Map.of("customers", 10L))).isInstanceOf(SQLException.class)
                    .hasMessageStartingWith("customers:");
        }
        assertThat(count("customers")).isEqualTo(1); // nothing of the failed batch stayed
    }

    @Test
    void snapshotsMakeRunsStartFromTheSameData() throws SQLException {
        try (BulkLoader loader = loader(new ArrayList<>())) {
            loader.load(Map.of("customers", 40L, "orders", 100L, "order_lines", 200L));
        }
        List<String> log = new ArrayList<>();
        try (DatabaseSnapshot snapshots = DatabaseSnapshot.connect(url, user, password, "app", log::add)) {
            assertThat(snapshots.list()).isEmpty();
            assertThat(snapshots.save("baseline")).isEqualTo(5);
            assertThat(snapshots.list()).containsExactly("baseline");

            // a load test changes the data: more rows, deletes
            try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
                st.execute("DELETE FROM app.order_lines");
                st.execute("INSERT INTO app.customers (email, first_name) VALUES ('later@example.com', 'Late')");
            }
            assertThat(count("order_lines")).isZero();
            assertThat(count("customers")).isEqualTo(42);

            assertThat(snapshots.restore("baseline")).isEqualTo(5);
            assertThat(count("customers")).isEqualTo(41);
            assertThat(count("orders")).isEqualTo(100);
            assertThat(count("order_lines")).isEqualTo(200);
            // sequences moved past the restored maximum: the next insert does not collide
            try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
                st.execute("INSERT INTO app.customers (email, first_name) VALUES ('next@example.com', 'Next')");
            }
            assertThat(query("SELECT max(id) FROM app.customers")).isEqualTo(42);
            // the snapshot schema is not an application table
            assertThat(snapshots.save("baseline")).isEqualTo(5);

            assertThatThrownBy(() -> snapshots.restore("missing")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no snapshot");
            assertThatThrownBy(() -> snapshots.save("Bad Name!")).isInstanceOf(IllegalArgumentException.class);
            snapshots.drop("baseline");
            assertThat(snapshots.list()).isEmpty();
        }
        assertThat(log).contains("snapshot: saved 5 tables as baseline", "snapshot: restored 5 tables from baseline");
    }

    @Test
    void snapshotsRefuseOtherDatabasesAndProductionLookingOnes() throws SQLException {
        try (DatabaseSnapshot s = DatabaseSnapshot.connect(url + "&ApplicationName=live-db", user, password, "app",
                x -> { })) {
            assertThatThrownBy(() -> s.save("baseline")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("production");
        }
    }
}
