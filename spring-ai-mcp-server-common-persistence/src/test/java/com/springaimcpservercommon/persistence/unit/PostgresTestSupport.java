package com.springaimcpservercommon.persistence.unit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared PostgreSQL 17 Testcontainer for the persistence ITs (Testcontainers 2 module {@code testcontainers-postgresql}).
 * One container per JVM; every test class isolates itself in its own schema ({@link #newSchema()}), which also gives
 * each class its own {@code dai_environment} row, migration history and partition functions.
 */
public final class PostgresTestSupport {

    /** Image used by all persistence ITs (minimum supported PostgreSQL is 15). */
    public static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer container;
    private static HikariDataSource dataSource;

    private PostgresTestSupport() {
    }

    /**
     * Shared pooled data source (started on first use; never closed — Testcontainers removes the container).
     *
     * @return the data source
     */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            // Without Docker: DAI_IT_JDBC_URL / DAI_IT_USER / DAI_IT_PASSWORD point at a local PostgreSQL 15+
            // (e.g. scripts/rule-engine/local-db.sh); every test class still gets its own random schema.
            String externalUrl = System.getenv("DAI_IT_JDBC_URL");
            HikariConfig config = new HikariConfig();
            if (externalUrl != null) {
                config.setJdbcUrl(externalUrl);
                config.setUsername(System.getenv().getOrDefault("DAI_IT_USER", "dai"));
                config.setPassword(System.getenv().getOrDefault("DAI_IT_PASSWORD", "dai"));
            } else {
                container = new PostgreSQLContainer(IMAGE);
                container.start();
                config.setJdbcUrl(container.getJdbcUrl());
                config.setUsername(container.getUsername());
                config.setPassword(container.getPassword());
            }
            config.setMaximumPoolSize(16);
            config.setPoolName("dai-it");
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /**
     * A fresh schema name.
     *
     * @return {@code it_<random hex>}
     */
    public static String newSchema() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "it_" + HexFormat.of().formatHex(bytes);
    }

    /**
     * Settings for a test unit: migrations on, Hibernate schema validation on.
     *
     * @param schema        schema
     * @param environmentId environment id
     * @param tier          tier
     * @return settings
     */
    public static DaiPersistenceSettings settings(String schema, String environmentId, String tier) {
        return new DaiPersistenceSettings(schema, true, null, environmentId, tier, true, 0);
    }

    /**
     * Starts a unit on a fresh schema (environment {@code it-env}, tier DEV).
     *
     * @return the started unit
     */
    public static DaiPersistenceUnit startFreshUnit() {
        return DaiPersistenceUnit.start(dataSource(), settings(newSchema(), "it-env", "DEV"));
    }

    /**
     * JDBC access for fixtures and assertions.
     *
     * @return a JdbcTemplate on the shared data source
     */
    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    /**
     * Inserts a USER principal ({@code dai_principal} belongs to V1; fixtures use SQL, not its entities).
     *
     * @param schema schema
     * @return principal id
     */
    public static UUID principal(String schema) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO " + schema + ".dai_principal (id, subject_type, issuer, external_id) "
                + "VALUES (?, 'USER', 'https://idp.test', ?)", id, "sub-" + id);
        return id;
    }

    /**
     * Inserts a workspace.
     *
     * @param schema schema
     * @return workspace id
     */
    public static UUID workspace(String schema) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO " + schema + ".dai_workspace (id, slug, name) VALUES (?, ?, 'Test workspace')",
                id, "ws-" + id.toString().substring(0, 8));
        return id;
    }
}
