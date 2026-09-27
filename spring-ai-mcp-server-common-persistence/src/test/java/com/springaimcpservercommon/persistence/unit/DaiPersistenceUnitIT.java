package com.springaimcpservercommon.persistence.unit;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migrations, Hibernate schema validation and the environment guard against a real PostgreSQL 17. */
class DaiPersistenceUnitIT {

    @Test
    void migratesValidatesAndRecordsTheEnvironment() {
        String schema = PostgresTestSupport.newSchema();
        try (DaiPersistenceUnit unit = DaiPersistenceUnit.start(PostgresTestSupport.dataSource(),
                PostgresTestSupport.settings(schema, "orders-dev", "DEV"))) {
            assertThat(unit.schema()).isEqualTo(schema);
            Map<String, Object> env = PostgresTestSupport.jdbc()
                    .queryForMap("SELECT environment_id, tier FROM " + schema + ".dai_environment");
            assertThat(env).containsEntry("environment_id", "orders-dev").containsEntry("tier", "DEV");
            Integer applied = PostgresTestSupport.jdbc().queryForObject(
                    "SELECT count(*) FROM " + schema + ".dai_schema_history WHERE success", Integer.class);
            assertThat(applied).isGreaterThanOrEqualTo(6);
            Long one = unit.readOnlyTransactions().execute(s ->
                    ((Number) unit.entityManager().createNativeQuery("SELECT 1").getSingleResult()).longValue());
            assertThat(one).isEqualTo(1L);
        }
    }

    @Test
    void restartWithTheSameIdentityIsAccepted() {
        String schema = PostgresTestSupport.newSchema();
        DaiPersistenceUnit.start(PostgresTestSupport.dataSource(), PostgresTestSupport.settings(schema, "orders-stage", "STAGE"))
                .close();
        try (DaiPersistenceUnit again = DaiPersistenceUnit.start(PostgresTestSupport.dataSource(),
                PostgresTestSupport.settings(schema, "orders-stage", "STAGE"))) {
            assertThat(again.schema()).isEqualTo(schema);
        }
    }

    @Test
    void anotherEnvironmentOrTierIsRefused() {
        String schema = PostgresTestSupport.newSchema();
        DaiPersistenceUnit.start(PostgresTestSupport.dataSource(), PostgresTestSupport.settings(schema, "orders-prod", "PROD"))
                .close();

        assertThatThrownBy(() -> DaiPersistenceUnit.start(PostgresTestSupport.dataSource(),
                PostgresTestSupport.settings(schema, "orders-stage", "STAGE")))
                .isInstanceOfSatisfying(StoreEnvironmentMismatchException.class, e -> {
                    assertThat(e.foundEnvironmentId()).isEqualTo("orders-prod");
                    assertThat(e.expectedEnvironmentId()).isEqualTo("orders-stage");
                });
        assertThatThrownBy(() -> DaiPersistenceUnit.start(PostgresTestSupport.dataSource(),
                PostgresTestSupport.settings(schema, "orders-prod", "DEV")))
                .isInstanceOf(StoreEnvironmentMismatchException.class);
    }

    @Test
    void closeLeavesTheCallersDataSourceOpen() throws Exception {
        DataSource ds = PostgresTestSupport.dataSource();
        PostgresTestSupport.startFreshUnit().close();

        try (Connection c = ds.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
    }
}
