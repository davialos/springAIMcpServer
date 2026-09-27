package com.springaimcpservercommon.persistence.unit;

import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings of the isolated {@code dynamic_ai} persistence unit (ADR-0019), resolved by the auto-configuration from
 * {@code dynamic.ai.agent.store.*} and {@code dynamic.ai.agent.environment.*} properties.
 *
 * <p>Defaults: a blank {@code schema} becomes {@value #DEFAULT_SCHEMA}; a {@code jdbcBatchSize} of {@code 0} becomes
 * {@value #DEFAULT_JDBC_BATCH_SIZE}. {@link #defaults(String, String)} builds the recommended production settings.
 *
 * @param schema              PostgreSQL schema owning every {@code dai_*} object; must match
 *                            {@code ^[a-z_][a-z0-9_]{0,62}$} (it is concatenated into native SQL, so it is validated
 *                            strictly)
 * @param migrate             whether to run the Flyway migrations at start-up
 * @param migrationDataSource optional data source with DDL rights used only by Flyway (the runtime data source may
 *                            then be a DML-only role); {@code null} = use the runtime data source
 * @param environmentId       identity of the environment owning the store (LLD-12 §3), e.g. {@code orders-prod-eu}
 * @param tier                environment tier: {@code DEV}, {@code TEST}, {@code STAGE} or {@code PROD}
 *                            ({@code UNKNOWN} must be resolved to {@code PROD} by the caller, LLD-12 §2.1)
 * @param validateSchema      whether Hibernate validates the mapping against the schema at start-up
 *                            ({@code hbm2ddl.auto=validate}); otherwise {@code none}
 * @param jdbcBatchSize       JDBC batch size for inserts/updates (1–1000; 0 = default)
 */
public record DaiPersistenceSettings(
        String schema,
        boolean migrate,
        @Nullable DataSource migrationDataSource,
        String environmentId,
        String tier,
        boolean validateSchema,
        int jdbcBatchSize) {

    /** Default schema name. */
    public static final String DEFAULT_SCHEMA = "dynamic_ai";

    /** Default JDBC batch size. */
    public static final int DEFAULT_JDBC_BATCH_SIZE = 50;

    /** Upper bound of {@link #jdbcBatchSize()}. */
    public static final int MAX_JDBC_BATCH_SIZE = 1000;

    /** Tiers accepted by the {@code dai_environment} CHECK constraint. */
    public static final Set<String> TIERS = Set.of("DEV", "TEST", "STAGE", "PROD");

    private static final Pattern SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern ENVIRONMENT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /**
     * Applies defaults and validates every component.
     *
     * @throws IllegalArgumentException if a component is invalid
     */
    public DaiPersistenceSettings {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(tier, "tier");
        if (schema.isBlank()) {
            schema = DEFAULT_SCHEMA;
        }
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema must match " + SCHEMA.pattern() + ": " + schema);
        }
        if (!ENVIRONMENT_ID.matcher(environmentId).matches()) {
            throw new IllegalArgumentException("environmentId must match " + ENVIRONMENT_ID.pattern() + ": " + environmentId);
        }
        if (!TIERS.contains(tier)) {
            throw new IllegalArgumentException("tier must be one of " + TIERS + ": " + tier);
        }
        if (jdbcBatchSize == 0) {
            jdbcBatchSize = DEFAULT_JDBC_BATCH_SIZE;
        }
        if (jdbcBatchSize < 1 || jdbcBatchSize > MAX_JDBC_BATCH_SIZE) {
            throw new IllegalArgumentException("jdbcBatchSize must be between 1 and " + MAX_JDBC_BATCH_SIZE + ": " + jdbcBatchSize);
        }
    }

    /**
     * Production defaults: schema {@value #DEFAULT_SCHEMA}, migrations on, runtime data source for migrations,
     * no Hibernate schema validation (ADR-0019: {@code none} in production, {@code validate} in tests), batch size
     * {@value #DEFAULT_JDBC_BATCH_SIZE}.
     *
     * @param environmentId environment identity
     * @param tier          environment tier
     * @return the settings
     */
    public static DaiPersistenceSettings defaults(String environmentId, String tier) {
        return new DaiPersistenceSettings(DEFAULT_SCHEMA, true, null, environmentId, tier, false, DEFAULT_JDBC_BATCH_SIZE);
    }

    /**
     * Returns a copy with another schema.
     *
     * @param newSchema schema name
     * @return new settings
     */
    public DaiPersistenceSettings withSchema(String newSchema) {
        return new DaiPersistenceSettings(newSchema, migrate, migrationDataSource, environmentId, tier, validateSchema,
                jdbcBatchSize);
    }

    /**
     * Returns a copy with Hibernate schema validation switched on or off.
     *
     * @param validate whether to validate
     * @return new settings
     */
    public DaiPersistenceSettings withValidateSchema(boolean validate) {
        return new DaiPersistenceSettings(schema, migrate, migrationDataSource, environmentId, tier, validate,
                jdbcBatchSize);
    }

    /**
     * Returns a copy with another environment identity.
     *
     * @param newEnvironmentId environment identity
     * @param newTier          environment tier
     * @return new settings
     */
    public DaiPersistenceSettings withEnvironment(String newEnvironmentId, String newTier) {
        return new DaiPersistenceSettings(schema, migrate, migrationDataSource, newEnvironmentId, newTier,
                validateSchema, jdbcBatchSize);
    }
}
