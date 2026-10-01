package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-data sampling and verification against PostgreSQL. Uses Testcontainers; set {@code LOADTEST_IT_JDBC_URL}
 * (+ {@code LOADTEST_IT_USER}/{@code LOADTEST_IT_PASSWORD}) to run against an existing database instead.
 */
class DatabaseSamplerIT {

    private static PostgreSQLContainer container;
    private static String url;
    private static String user;
    private static String password;
    private static final String SCHEMA = "loadtest_it";

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
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TABLE " + SCHEMA + ".customers (id bigserial PRIMARY KEY, email text UNIQUE, "
                    + "password_hash text, external_ref uuid DEFAULT gen_random_uuid())");
            st.execute("CREATE TABLE " + SCHEMA + ".product (sku text PRIMARY KEY, price numeric(10,2))");
            st.execute("CREATE TABLE " + SCHEMA + ".orders (id bigserial PRIMARY KEY, "
                    + "customer_id bigint REFERENCES " + SCHEMA + ".customers(id))");
            st.execute("INSERT INTO " + SCHEMA + ".customers(email, password_hash) "
                    + "SELECT 'c' || g || '@example.com', 'h' FROM generate_series(1, 40) g");
            st.execute("INSERT INTO " + SCHEMA + ".product SELECT 'SKU-' || g, g FROM generate_series(1, 5) g");
            st.execute("INSERT INTO " + SCHEMA + ".orders(customer_id) SELECT 1 + g % 40 FROM generate_series(1, 10) g");
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
        if (container != null) {
            container.stop();
        }
    }

    private static DatabaseSampler sampler() throws SQLException {
        return DatabaseSampler.connect(url, user, password, SCHEMA);
    }

    @Test
    void readsTablesAndSamplesDistinctRandomValues() throws SQLException {
        try (DatabaseSampler db = sampler()) {
            assertThat(db.tables()).extracting(DbTable::name).containsExactlyInAnyOrder("customers", "product", "orders");
            assertThat(db.tables()).filteredOn(t -> t.name().equals("product")).singleElement()
                    .satisfies(t -> assertThat(t.primaryKey()).containsExactly("sku"));
            List<Object> ids = db.sample(new PoolRef(SCHEMA, "customers", "id"), 15);
            assertThat(ids).hasSize(15).doesNotHaveDuplicates().allMatch(v -> v instanceof Long);
            assertThat(db.sample(new PoolRef(SCHEMA, "product", "sku"), 50)).hasSize(5);
            assertThat(db.sample(new PoolRef(SCHEMA, "customers", "external_ref"), 2)).allMatch(v -> v instanceof String);
        }
    }

    @Test
    void checksWhichValuesExistTypedByColumn() throws SQLException {
        try (DatabaseSampler db = sampler()) {
            PoolRef ids = new PoolRef(SCHEMA, "customers", "id");
            assertThat(db.existing(ids, List.of(1L, "2", 99999L, "not-a-number"))).containsExactly(1L, "2");
            assertThat(db.existing(new PoolRef(SCHEMA, "customers", "email"), List.of("c3@example.com", "nobody@x")))
                    .containsExactly("c3@example.com");
        }
    }

    @Test
    void refusesTablesAndColumnsMissingFromTheMetadata() throws SQLException {
        try (DatabaseSampler db = sampler()) {
            assertThat(db.has(new PoolRef(SCHEMA, "customers", "email\" OR 1=1 --"))).isFalse();
            assertThatThrownBy(() -> db.sample(new PoolRef(SCHEMA, "pg_authid", "rolpassword"), 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void collectorFillsPoolsAndVerifiesUserValues() throws SQLException {
        ApiCatalog catalog = CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop())));
        try (DatabaseSampler db = sampler()) {
            TableIndex index = new TableIndex(catalog.entities(), db.tables());
            DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, Map.of()));
            UserData user = new UserData(Map.of("CreateOrderRequest.customerId", List.of(1L, 2L, 424242L),
                    "deliveryNotes", List.of("leave at door")), Map.of(), Map.of());
            List<String> log = new ArrayList<>();
            RealDataCollector.Result r = new RealDataCollector(log::add)
                    .collect(catalog, plan, index, db, null, user, 10, true);
            assertThat(r.pools()).containsKeys("loadtest_it.customers.id", "loadtest_it.orders.id",
                    "loadtest_it.product.sku", "loadtest_it.customers.email");
            assertThat(r.pools().get("loadtest_it.customers.id")).hasSize(10);
            assertThat(r.user().fields().get("CreateOrderRequest.customerId")).containsExactly(1L, 2L);
            assertThat(r.user().fields().get("deliveryNotes")).containsExactly("leave at door"); // unbound: untouched
            assertThat(log).anyMatch(l -> l.contains("2/3 values exist"));
            assertThat(String.join("\n", log)).doesNotContain("password_hash");
        }
    }
}
