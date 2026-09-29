package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.usage.UsageBucket;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsageSeriesTest {

    @Test
    void dailyAggregationSumsHoursPerUtcDayAndCurrency() {
        List<UsageBucket> hours = List.of(
                new UsageBucket(Instant.parse("2026-09-28T23:00:00Z"),
                        new UsageTotals(1, 10, 5, 0, Map.of("EUR", 100L))),
                new UsageBucket(Instant.parse("2026-09-29T00:00:00Z"),
                        new UsageTotals(2, 20, 10, 4, Map.of("EUR", 200L))),
                new UsageBucket(Instant.parse("2026-09-29T13:00:00Z"),
                        new UsageTotals(3, 30, 15, 0, Map.of("EUR", 50L, "USD", 7L))));

        List<UsageAdminController.PointDto> days = UsageAdminController.daily(hours);

        assertThat(days).hasSize(2);
        assertThat(days.get(0).start()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(days.get(0).totals().calls()).isEqualTo(1);
        assertThat(days.get(1).start()).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
        assertThat(days.get(1).totals().calls()).isEqualTo(5);
        assertThat(days.get(1).totals().totalTokens()).isEqualTo(20 + 10 + 4 + 30 + 15);
        assertThat(days.get(1).totals().costMicrosByCurrency()).containsEntry("EUR", 250L).containsEntry("USD", 7L);
    }
}
