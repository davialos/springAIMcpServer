package com.springaimcpservercommon.persistence.config.support;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.identity.PrincipalDirectory;
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceSettings;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test support for the identity/config/usage integration tests: one shared PostgreSQL 17 container per JVM and a
 * fresh schema (migrated by {@link DaiPersistenceUnit} with Flyway, Hibernate schema validation on) per test class.
 *
 * <p>The pooled {@code DataSource} deliberately does NOT set a search_path, so native SQL that forgets to qualify
 * tables with {@link DaiStore#schema()} fails in these tests.
 */
public final class DaiTestDatabase implements AutoCloseable {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final String schema;
    private final HikariDataSource dataSource;
    private final DaiPersistenceUnit unit;
    private final JdbcTemplate jdbc;

    private DaiTestDatabase(String schema, HikariDataSource dataSource, DaiPersistenceUnit unit) {
        this.schema = schema;
        this.dataSource = dataSource;
        this.unit = unit;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Starts the shared container if needed and migrates a new, randomly named schema.
     *
     * @return the database handle; close it in {@code @AfterAll}
     */
    public static DaiTestDatabase create() {
        startContainerOnce();
        String schema = "dai_it_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(24);
        config.setPoolName(schema);
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            DaiPersistenceUnit unit = DaiPersistenceUnit.start(dataSource,
                    new DaiPersistenceSettings(schema, true, null, "it", "TEST", true, 50));
            return new DaiTestDatabase(schema, dataSource, unit);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    private static synchronized void startContainerOnce() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start(); // stopped by the Testcontainers resource reaper at JVM exit
        }
    }

    /**
     * The persistence unit under test.
     *
     * @return the store
     */
    public DaiStore store() {
        return unit;
    }

    /**
     * Plain JDBC for assertions and for bypassing the stores (trigger/constraint tests).
     *
     * @return a JdbcTemplate on the same database
     */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Qualifies a table name with the test schema.
     *
     * @param table unqualified table name
     * @return {@code schema.table}
     */
    public String table(String table) {
        return schema + "." + table;
    }

    /**
     * Creates (or resolves) a user principal.
     *
     * @param externalId external subject id
     * @return principal id
     */
    public UUID user(String externalId) {
        return new PrincipalDirectory(unit).resolve(SubjectType.USER, "https://idp.example.test", externalId, null);
    }

    /**
     * Creates a workspace with a unique slug.
     *
     * @param slugPrefix slug prefix ({@code [a-z]+})
     * @return workspace id
     */
    public UUID workspace(String slugPrefix) {
        String slug = slugPrefix + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);
        return new WorkspaceStore(unit).create(slug, slugPrefix, null, null, Classification.INTERNAL, null).id();
    }

    /**
     * All messages of an exception chain, for asserting constraint and trigger names.
     *
     * @param error the exception
     * @return messages joined with " | "
     */
    public static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
            if (t.getCause() == t) {
                break;
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        try {
            unit.close();
        } catch (Exception e) {
            throw new IllegalStateException("closing the persistence unit failed", e);
        } finally {
            dataSource.close();
        }
    }
}
