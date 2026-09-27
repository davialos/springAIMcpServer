package com.springaimcpservercommon.persistence.unit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The framework's isolated persistence unit (ADR-0019): Flyway, a Hibernate entity manager factory, a
 * {@link JpaTransactionManager} and transaction templates, all created programmatically and owned by this object.
 * None of them is ever registered as a Spring bean, so the host's JPA/Flyway auto-configuration, its
 * {@code @Transactional} default transaction manager and its repositories are never affected.
 *
 * <p>Start-up sequence ({@link #start(DataSource, DaiPersistenceSettings)}):
 * <ol>
 *   <li>Flyway migrates schema {@code settings.schema()} (created if missing) from
 *       {@value #MIGRATION_LOCATION} with history table {@value #HISTORY_TABLE}, using the migration data source if
 *       one is configured (DDL role) — skipped when {@code migrate=false}.</li>
 *   <li>The entity manager factory is built over the runtime data source with
 *       {@code hibernate.default_schema=settings.schema()}; Hibernate validates the mapping when
 *       {@code validateSchema=true}.</li>
 *   <li>Environment guard (LLD-12 §3): the single {@code dai_environment} row is inserted when absent; when it
 *       exists and names another environment or tier, start-up fails with
 *       {@link StoreEnvironmentMismatchException}.</li>
 * </ol>
 * If any step fails, everything created so far is closed and the exception propagates; the auto-configuration decides
 * whether that disables the feature or fails the host ("fail the feature, not the host", LLD-12 §4).
 *
 * <p>{@link #close()} closes the entity manager factory. The data sources belong to the caller and are never closed.
 */
public final class DaiPersistenceUnit implements DaiStore, AutoCloseable {

    /** JPA persistence unit name. */
    public static final String PERSISTENCE_UNIT_NAME = "dynamicAi";

    /** Flyway migration location of the library. */
    public static final String MIGRATION_LOCATION = "classpath:db/dynamic-ai/migration";

    /** Flyway history table (inside the unit's schema). */
    public static final String HISTORY_TABLE = "dai_schema_history";

    /** Root package scanned for entities. */
    public static final String ENTITY_PACKAGE = "com.springaimcpservercommon.persistence";

    /** Timeout applied to every transaction template of the unit. */
    public static final int TRANSACTION_TIMEOUT_SECONDS = 30;

    /**
     * A {@code persistence.xml} location that never exists, so the unit never parses (or clashes with) the host's
     * own {@code META-INF/persistence.xml}.
     */
    private static final String NO_PERSISTENCE_XML = "classpath*:META-INF/dynamic-ai-no-persistence.xml";

    private static final Logger log = LoggerFactory.getLogger(DaiPersistenceUnit.class);

    private final LocalContainerEntityManagerFactoryBean factoryBean;
    private final String schema;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnlyTransactions;
    private final TransactionTemplate newTransactions;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DaiPersistenceUnit(LocalContainerEntityManagerFactoryBean factoryBean, String schema) {
        this.factoryBean = factoryBean;
        this.schema = schema;
        EntityManagerFactory emf = Objects.requireNonNull(factoryBean.getObject(), "entity manager factory");
        JpaTransactionManager transactionManager = new JpaTransactionManager(emf);
        this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        this.transactions = template(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED, false);
        this.readOnlyTransactions = template(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED, true);
        this.newTransactions = template(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW, false);
    }

    /**
     * Migrates the schema (optional), builds the unit and checks the store's environment identity.
     *
     * @param dataSource runtime data source (owned by the caller; never closed by the unit)
     * @param settings   unit settings
     * @return the started unit
     * @throws StoreEnvironmentMismatchException if the store belongs to another environment
     * @throws RuntimeException                  if migration, entity manager factory creation or schema validation
     *                                           fails
     */
    public static DaiPersistenceUnit start(DataSource dataSource, DaiPersistenceSettings settings) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(settings, "settings");
        if (settings.migrate()) {
            DataSource migrationDataSource = settings.migrationDataSource();
            migrate(migrationDataSource != null ? migrationDataSource : dataSource, settings.schema());
        }
        LocalContainerEntityManagerFactoryBean factoryBean = buildEntityManagerFactory(dataSource, settings);
        DaiPersistenceUnit unit = null;
        try {
            unit = new DaiPersistenceUnit(factoryBean, settings.schema());
            unit.verifyEnvironment(settings.environmentId(), settings.tier());
            log.info("dynamic_ai persistence unit started (schema {}, environment {} / {})",
                    settings.schema(), settings.environmentId(), settings.tier());
            return unit;
        } catch (RuntimeException | Error e) {
            if (unit != null) {
                unit.close();
            } else {
                factoryBean.destroy();
            }
            throw e;
        }
    }

    private static void migrate(DataSource dataSource, String schema) {
        Flyway flyway = Flyway.configure(DaiPersistenceUnit.class.getClassLoader())
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(MIGRATION_LOCATION)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                // our SQL contains no placeholders; never let host-style ${...} text be interpreted
                .placeholderReplacement(false)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        MigrateResult result = flyway.migrate();
        log.info("dynamic_ai schema {} migrated: {} migration(s) applied", schema, result.migrationsExecuted);
    }

    private static LocalContainerEntityManagerFactoryBean buildEntityManagerFactory(DataSource dataSource,
                                                                                    DaiPersistenceSettings settings) {
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false);
        vendorAdapter.setShowSql(false);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.default_schema", settings.schema());
        properties.put("hibernate.hbm2ddl.auto", settings.validateSchema() ? "validate" : "none");
        properties.put("hibernate.jdbc.time_zone", "UTC");
        properties.put("hibernate.jdbc.batch_size", Integer.toString(settings.jdbcBatchSize()));
        properties.put("hibernate.order_inserts", "true");
        properties.put("hibernate.order_updates", "true");
        properties.put("hibernate.jdbc.batch_versioned_data", "true");
        properties.put("hibernate.type.json_format_mapper", PassThroughJsonFormatMapper.INSTANCE);
        // never try to bind our session factory into a JNDI context of the host
        properties.put("hibernate.session_factory_name_is_jndi", "false");
        properties.put("hibernate.query.fail_on_pagination_over_collection_fetch", "true");
        properties.put("hibernate.generate_statistics", "false");

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setBeanClassLoader(DaiPersistenceUnit.class.getClassLoader());
        factoryBean.setDataSource(dataSource);
        factoryBean.setPersistenceXmlLocation(NO_PERSISTENCE_XML);
        factoryBean.setPersistenceUnitName(PERSISTENCE_UNIT_NAME);
        factoryBean.setPackagesToScan(ENTITY_PACKAGE);
        // explicit empty list: never pick up a META-INF/orm.xml of the host
        factoryBean.setMappingResources();
        factoryBean.setJpaVendorAdapter(vendorAdapter);
        factoryBean.setJpaPropertyMap(properties);
        factoryBean.setSharedCacheMode(SharedCacheMode.NONE);
        factoryBean.setValidationMode(ValidationMode.NONE);
        factoryBean.afterPropertiesSet();
        return factoryBean;
    }

    private static TransactionTemplate template(JpaTransactionManager transactionManager, int propagation,
                                                boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        template.setReadOnly(readOnly);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private void verifyEnvironment(String environmentId, String tier) {
        transactions.executeWithoutResult(status -> {
            List<Object[]> rows = readEnvironment();
            if (rows.isEmpty()) {
                entityManager.createNativeQuery("INSERT INTO " + schema + ".dai_environment (singleton, environment_id, tier) "
                                + "VALUES (true, ?1, ?2) ON CONFLICT (singleton) DO NOTHING")
                        .setParameter(1, environmentId)
                        .setParameter(2, tier)
                        .executeUpdate();
                rows = readEnvironment();
            }
            if (rows.size() != 1) {
                throw new IllegalStateException("dai_environment must hold exactly one row, found " + rows.size());
            }
            String foundId = String.valueOf(rows.getFirst()[0]);
            String foundTier = String.valueOf(rows.getFirst()[1]);
            if (!environmentId.equals(foundId) || !tier.equals(foundTier)) {
                throw new StoreEnvironmentMismatchException(environmentId, tier, foundId, foundTier);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readEnvironment() {
        return entityManager.createNativeQuery("SELECT environment_id, tier FROM " + schema + ".dai_environment")
                .getResultList();
    }

    @Override
    public EntityManager entityManager() {
        return entityManager;
    }

    @Override
    public TransactionOperations transactions() {
        return transactions;
    }

    @Override
    public TransactionOperations readOnlyTransactions() {
        return readOnlyTransactions;
    }

    @Override
    public TransactionOperations newTransactions() {
        return newTransactions;
    }

    @Override
    public String schema() {
        return schema;
    }

    /**
     * Closes the entity manager factory (idempotent). The data sources are owned by the caller and stay open.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            factoryBean.destroy();
            log.info("dynamic_ai persistence unit closed");
        }
    }
}
