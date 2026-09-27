package com.springaimcpservercommon.persistence.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageValuesTest {

    @Test
    void periodsAreUtcCalendarWindows() {
        Instant at = Instant.parse("2026-02-28T23:30:00Z");
        assertThat(BudgetPeriod.DAY.windowContaining(at))
                .isEqualTo(new UsageWindow(Instant.parse("2026-02-28T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")));
        assertThat(BudgetPeriod.MONTH.windowContaining(at))
                .isEqualTo(new UsageWindow(Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")));
    }

    @Test
    void deltaTruncatesToTheHourAndMergesSameBucket() {
        UUID ws = UUID.randomUUID();
        UsageDelta a = new UsageDelta(Instant.parse("2026-09-28T10:59:59Z"), ws, null, null, "p", "m", "EUR", 1, 10, 5, 1, 7);
        UsageDelta b = new UsageDelta(Instant.parse("2026-09-28T10:00:00Z"), ws, null, null, "p", "m", "EUR", 2, 1, 1, 0, 3);

        assertThat(a.bucketStart()).isEqualTo(Instant.parse("2026-09-28T10:00:00Z"));
        UsageDelta sum = a.plus(b);
        assertThat(sum.calls()).isEqualTo(3);
        assertThat(sum.costMicros()).isEqualTo(10);
        assertThatThrownBy(() -> new UsageDelta(a.bucketStart(), ws, null, null, "p", "m", null, 1, 1, 1, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsMirrorTheCheckConstraints() {
        assertThatThrownBy(() -> new BudgetLimits(null, null, null, 80, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetLimits(null, 10L, null, 80, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetLimits(10L, null, "EUR", 80, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetLimits.cost(10, "eur")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetLimits(10L, null, null, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(BudgetLimits.tokens(5).softLimitPct()).isEqualTo(BudgetLimits.DEFAULT_SOFT_LIMIT_PCT);
    }

    @Test
    void costIsRoundedHalfUpToWholeMicros() {
        ModelPriceView price = new ModelPriceView("p", "m", Instant.EPOCH, "USD", 1_500_000, 3_000_000, 0);
        assertThat(price.costMicros(1, 0, 0)).isEqualTo(2); // 1.5 micros → 2
        assertThat(price.costMicros(1_000_000, 1_000_000, 0)).isEqualTo(4_500_000);
    }
}
