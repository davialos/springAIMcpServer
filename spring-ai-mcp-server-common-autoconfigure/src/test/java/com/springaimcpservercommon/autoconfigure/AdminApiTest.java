package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminApiTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    void pageDefaultsAndBounds() {
        assertThat(AdminApi.page(null, null)).isEqualTo(new PageRequest(0, AdminApi.DEFAULT_LIMIT));
        assertThat(AdminApi.page(200, 10)).isEqualTo(new PageRequest(10, 200));
        assertThatThrownBy(() -> AdminApi.page(201, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.page(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.page(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowDefaultsToLast24Hours() {
        TimeRange range = AdminApi.window(null, null, NOW);
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void windowHonoursExplicitBounds() {
        TimeRange range = AdminApi.window("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", NOW);
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    void windowRejectsInvertedOrHugeOrUnparsableRanges() {
        assertThatThrownBy(() -> AdminApi.window("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.window("2020-01-01T00:00:00Z", "2026-09-01T00:00:00Z", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminApi.window("yesterday", null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be an ISO-8601 instant");
    }
}
