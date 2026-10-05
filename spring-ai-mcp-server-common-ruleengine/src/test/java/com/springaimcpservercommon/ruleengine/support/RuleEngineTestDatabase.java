package com.springaimcpservercommon.ruleengine.support;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A PostgreSQL schema migrated with the real {@code db/dynamic-ai/migration} scripts (V1..V11) and filled with
 * {@code scripts/rule-engine/sample-data.sql}.
 *
 * <p>By default a Testcontainers PostgreSQL 17 is used. Without Docker, point the tests at a local server (for
 * example one started by {@code scripts/rule-engine/local-db.sh}) with the environment variables
 * {@code DAI_RULE_IT_JDBC_URL}, {@code DAI_RULE_IT_USER} and {@code DAI_RULE_IT_PASSWORD}; a random schema is
 * created in it and dropped afterwards.
 */
public final class RuleEngineTestDatabase implements AutoCloseable {

    private static PostgreSQLContainer container;

    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();
    private final String schema = "re_it_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

    private RuleEngineTestDatabase() {
    }

    public static RuleEngineTestDatabase create(boolean withSampleData) {
        RuleEngineTestDatabase db = new RuleEngineTestDatabase();
        String url = System.getenv("DAI_RULE_IT_JDBC_URL");
        if (url != null) {
            db.dataSource.setUrl(url);
            db.dataSource.setUser(System.getenv().getOrDefault("DAI_RULE_IT_USER", "dai"));
            db.dataSource.setPassword(System.getenv().getOrDefault("DAI_RULE_IT_PASSWORD", "dai"));
        } else {
            PostgreSQLContainer pg = startContainer();
            db.dataSource.setUrl(pg.getJdbcUrl());
            db.dataSource.setUser(pg.getUsername());
            db.dataSource.setPassword(pg.getPassword());
        }
        Flyway.configure()
                .dataSource(db.dataSource)
                .schemas(db.schema)
                .defaultSchema(db.schema)
                .createSchemas(true)
                .table("dai_schema_history")
                .locations("classpath:db/dynamic-ai/migration")
                .placeholderReplacement(false)
                .load()
                .migrate();
        if (withSampleData) {
            db.runScript("sample-data.sql");
        }
        return db;
    }

    private static synchronized PostgreSQLContainer startContainer() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start(); // stopped by the Testcontainers resource reaper at JVM exit
        }
        return container;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String schema() {
        return schema;
    }

    /** Runs a classpath SQL script on one connection whose schema is the test schema. */
    public void runScript(String resource) {
        try (InputStream in = RuleEngineTestDatabase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                c.setSchema(schema);
                st.execute(sql);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("could not run " + resource, e);
        }
    }

    /** Runs one statement in the test schema; returns the update count (or 0). */
    public int execute(String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setSchema(schema);
            st.execute(sql);
            return Math.max(st.getUpdateCount(), 0);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Runs a single-value query in the test schema. */
    public long queryLong(String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setSchema(schema);
            try (var rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (SQLException e) {
            // best effort: the schema name is random and the container is disposable
        }
    }
}
