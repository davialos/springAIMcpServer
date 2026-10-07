package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.cli.LoadTestCli;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database-structure feature through its public surfaces: {@link LoadTestGenerator#readSchema}, the suite's
 * {@code data/schema.sql} and the {@code schema} CLI command. Uses Testcontainers; set
 * {@code LOADTEST_IT_JDBC_URL} (+ {@code LOADTEST_IT_USER}/{@code LOADTEST_IT_PASSWORD}) to use an existing database.
 */
class SchemaSnapshotIT {

    private static final String SCHEMA = "loadtest_snapshot_it";

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
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TABLE " + SCHEMA + ".customers (id bigserial PRIMARY KEY, email text UNIQUE NOT NULL)");
            st.execute("CREATE TABLE " + SCHEMA + ".orders (id bigserial PRIMARY KEY, customer_id bigint "
                    + "REFERENCES " + SCHEMA + ".customers(id))");
            st.execute("INSERT INTO " + SCHEMA + ".customers(email) VALUES ('secret.person@example.com')");
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

    private LoadTestGenerator.Builder generator(List<String> log) {
        return LoadTestGenerator.builder().database(url, user, password).databaseSchema(SCHEMA).log(log::add);
    }

    @Test
    void readsAndRendersTheConfiguredDatabase() throws SQLException {
        List<String> log = new ArrayList<>();
        LoadTestGenerator g = generator(log).build();

        SchemaSnapshot snapshot = g.readSchema(true);
        String ddl = g.ddl(snapshot);

        assertThat(snapshot.tables()).extracting(SchemaSnapshot.Table::name).containsExactly("customers", "orders");
        assertThat(ddl).contains("CREATE TABLE " + SCHEMA + ".customers (")
                .contains("ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES " + SCHEMA
                        + ".customers(id)")
                .contains("-- rows: 1")
                .doesNotContain("secret.person"); // structure and counts only, never row data
        assertThat(log).anyMatch(l -> l.startsWith("schema: 2 tables, 0 views read from"));
    }

    @Test
    void passwordsNeverReachTheLog() throws SQLException {
        List<String> log = new ArrayList<>();
        String withPassword = url + (url.contains("?") ? "&" : "?") + "password=hunter2";
        generator(log).database(withPassword, user, password).build().readSchema(false);

        assertThat(String.join("\n", log)).contains("password=***").doesNotContain("hunter2");
    }

    @Test
    void readSchemaWithoutADatabaseExplainsWhy() {
        assertThatThrownBy(() -> LoadTestGenerator.builder().noDatabase().build().readSchema(false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> LoadTestGenerator.builder().build().readSchema(false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no database configured");
    }

    @Test
    void generateWritesTheDatabaseStructureIntoTheSuite(@TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        Path suite = dir.resolve("suite");

        generator(log).project(Fixtures.sampleShop()).outDir(suite).build().generate();

        assertThat(suite.resolve("data/schema.sql")).exists();
        assertThat(Files.readString(suite.resolve("data/schema.sql"))).contains("CREATE TABLE " + SCHEMA + ".orders (")
                .doesNotContain("secret.person");
        assertThat(log).anyMatch(l -> l.contains("database structure written to schema.sql"));
    }

    @Test
    void generateCanSkipTheSnapshot(@TempDir Path dir) {
        Path suite = dir.resolve("suite");

        generator(new ArrayList<>()).project(Fixtures.sampleShop()).outDir(suite).schemaSnapshot(false).build()
                .generate();

        assertThat(suite.resolve("data/schema.sql")).doesNotExist();
        assertThat(suite.resolve("data/real.json")).exists();
    }

    @Test
    void generateWithoutADatabaseWritesNoSnapshotAndStillSucceeds(@TempDir Path dir) {
        Path suite = dir.resolve("suite");

        LoadTestGenerator.builder().project(Fixtures.sampleShop()).outDir(suite).noDatabase().build().generate();

        assertThat(suite.resolve("data/schema.sql")).doesNotExist();
        assertThat(suite.resolve("main.js")).exists();
    }

    @Test
    void theSchemaCommandPrintsDdlToStdoutAndDiagnosticsToStderr(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LoadTestCli cli = new LoadTestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));

        int code = cli.execute(new String[]{"schema", "--db-url", url, "--db-user", user, "--db-password",
                password == null ? "" : password, "--db-schema", SCHEMA});

        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        String ddl = out.toString(StandardCharsets.UTF_8);
        assertThat(ddl).startsWith("-- Database schema snapshot").contains("CREATE TABLE " + SCHEMA + ".customers (");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("[loadtest] schema: 2 tables");

        Path file = dir.resolve("out/schema.sql");
        out.reset();
        int toFile = cli.execute(new String[]{"schema", "--db-url", url, "--db-user", user, "--db-password",
                password == null ? "" : password, "--db-schema", SCHEMA, "--row-counts", "--out", file.toString()});
        assertThat(toFile).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(file)).contains("-- rows: 1");
    }
}
