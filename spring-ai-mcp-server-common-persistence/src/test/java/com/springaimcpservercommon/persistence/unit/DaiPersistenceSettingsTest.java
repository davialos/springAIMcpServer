package com.springaimcpservercommon.persistence.unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaiPersistenceSettingsTest {

    @Test
    void appliesDefaults() {
        DaiPersistenceSettings s = new DaiPersistenceSettings("", true, null, "orders-dev", "DEV", false, 0);

        assertThat(s.schema()).isEqualTo("dynamic_ai");
        assertThat(s.jdbcBatchSize()).isEqualTo(50);
        assertThat(DaiPersistenceSettings.defaults("orders-prod-eu", "PROD").schema()).isEqualTo("dynamic_ai");
    }

    @Test
    void validatesSchemaTierEnvironmentAndBatchSize() {
        assertThatThrownBy(() -> new DaiPersistenceSettings("Dynamic", true, null, "e", "DEV", false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DaiPersistenceSettings("x; drop table y", true, null, "e", "DEV", false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DaiPersistenceSettings("dynamic_ai", true, null, "e", "UNKNOWN", false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DaiPersistenceSettings("dynamic_ai", true, null, "-bad", "DEV", false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DaiPersistenceSettings("dynamic_ai", true, null, "e", "DEV", false, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
