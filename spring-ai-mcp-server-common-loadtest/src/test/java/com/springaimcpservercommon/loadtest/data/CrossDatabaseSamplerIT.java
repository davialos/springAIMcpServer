package com.springaimcpservercommon.loadtest.data;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-data sampling on MySQL and SQL Server (PostgreSQL: {@link DatabaseSamplerIT}): table discovery scoped to
 * the application's database, dialect-specific random sampling, foreign keys (single and composite, sampled as
 * tuples), unique indexes, column sizes, required and database-generated columns, and value checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossDatabaseSamplerIT {

    private MySQLContainer mysql;
    private MSSQLServerContainer mssql;

    /** Starts both databases once; the parameter source may run before {@code @BeforeAll}. */
    @BeforeAll
    synchronized void start() throws SQLException {
        if (mysql != null) {
            return;
        }
        mysql = new MySQLContainer("mysql:8.4");
        mssql = new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
        mysql.start();
        mssql.start();
        create(mysql, "BIGINT AUTO_INCREMENT", "VARCHAR(80)");
        try (Connection c = DriverManager.getConnection(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE app"); // an application database, not master
        }
        create(mssql, "BIGINT IDENTITY(1,1)", "NVARCHAR(80)");
    }

    @AfterAll
    void stop() {
        if (mysql != null) {
            mysql.stop();
        }
        if (mssql != null) {
            mssql.stop();
        }
    }

    /** Names only: JUnit closes AutoCloseable arguments after each invocation, and containers are closeable. */
    static Stream<String> databases() {
        return Stream.of("mysql", "mssql");
    }

    private JdbcDatabaseContainer<?> db(String name) throws SQLException {
        start();
        return name.equals("mysql") ? mysql : mssql;
    }

    private static String url(JdbcDatabaseContainer<?> db) {
        return db instanceof MSSQLServerContainer ? db.getJdbcUrl() + ";databaseName=app" : db.getJdbcUrl();
    }

    private static void create(JdbcDatabaseContainer<?> db, String identity, String text)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE customers (id " + identity + " PRIMARY KEY, email " + text
                    + " NOT NULL UNIQUE, nickname " + text + " NULL, created_at DATE DEFAULT '2026-01-01')");
            st.execute("CREATE TABLE order_lines (order_id BIGINT NOT NULL, line_no INT NOT NULL, sku " + text
                    + " NOT NULL, PRIMARY KEY (order_id, line_no))");
            st.execute("CREATE TABLE shipment_items (id BIGINT NOT NULL PRIMARY KEY, customer_id BIGINT NOT NULL,"
                    + " order_id BIGINT NOT NULL, line_no INT NOT NULL,"
                    + " CONSTRAINT fk_ship_customer FOREIGN KEY (customer_id) REFERENCES customers (id),"
                    + " CONSTRAINT fk_ship_line FOREIGN KEY (order_id, line_no) REFERENCES order_lines (order_id, line_no))");
            for (int i = 1; i <= 30; i++) {
                st.execute("INSERT INTO customers (email) VALUES ('c" + i + "@example.com')");
            }
            st.execute("INSERT INTO order_lines (order_id, line_no, sku) VALUES (1, 1, 'A'), (1, 2, 'B'), (2, 1, 'C')");
        }
    }

    private static DatabaseSampler sampler(JdbcDatabaseContainer<?> db) throws SQLException {
        return DatabaseSampler.connect(url(db), db.getUsername(), db.getPassword(), null);
    }

    private static DbTable table(DatabaseSampler s, String name) {
        return s.tables().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void readsOnlyTheApplicationsTablesWithTheirKeys(String name) throws SQLException {
        try (DatabaseSampler s = sampler(db(name))) {
            // MySQL exposes databases as catalogs: system databases must not leak into the table list
            assertThat(s.tables()).extracting(t -> t.name().toLowerCase())
                    .containsExactlyInAnyOrder("customers", "order_lines", "shipment_items");
            DbTable customers = table(s, "customers");
            assertThat(customers.primaryKey()).containsExactly("id");
            assertThat(customers.uniqueColumns()).containsExactly("email");
            assertThat(customers.columnSizes()).containsEntry("email", 80);
            assertThat(customers.generatedColumns()).contains("id", "created_at");
            assertThat(customers.requiredColumns()).containsExactly("email");
            DbTable items = table(s, "shipment_items");
            assertThat(items.foreignKeys()).containsOnlyKeys("customer_id");
            assertThat(items.foreignKeys().get("customer_id").column()).isEqualTo("id");
            assertThat(items.compositeForeignKeys()).singleElement().satisfies(k -> {
                assertThat(k.columns()).containsExactly("order_id", "line_no");
                assertThat(k.target().column()).isEqualTo("order_id,line_no");
            });
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void samplesValuesAndTuplesAndChecksExistence(String name) throws SQLException {
        try (DatabaseSampler s = sampler(db(name))) {
            DbTable customers = table(s, "customers");
            PoolRef ids = new PoolRef(customers.schema(), customers.name(), "id");
            List<Object> sample = s.sample(ids, 12);
            assertThat(sample).hasSize(12).doesNotHaveDuplicates().allMatch(v -> v instanceof Long);
            assertThat(s.existing(ids, List.of(1L, "2", 999L))).containsExactly(1L, "2");
            PoolRef emails = new PoolRef(customers.schema(), customers.name(), "email");
            assertThat(s.existing(emails, List.of("c3@example.com", "nobody@x"))).containsExactly("c3@example.com");

            DbTable lines = table(s, "order_lines");
            List<Object> tuples = s.sample(new PoolRef(lines.schema(), lines.name(), "order_id,line_no"), 10);
            assertThat(tuples).hasSize(3).allSatisfy(t -> assertThat(t).isInstanceOf(List.class));
            assertThat(tuples).extracting(Object::toString).containsExactlyInAnyOrder("[1, 1]", "[1, 2]", "[2, 1]");
        }
    }
}
