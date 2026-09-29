package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.usage.BudgetLimits;
import com.springaimcpservercommon.persistence.usage.BudgetTarget;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BudgetMathTest {

    @Test
    void tokenLimitUsesAllTokenKinds() {
        UsageTotals totals = new UsageTotals(3, 400, 100, 50, Map.of());

        assertThat(BudgetMath.percentUsed(BudgetLimits.tokens(1_000), totals)).isCloseTo(55.0, within(0.001));
    }

    @Test
    void costLimitUsesTheBudgetCurrencyOnly() {
        UsageTotals totals = new UsageTotals(1, 0, 0, 0, Map.of("EUR", 800_000L, "USD", 9_000_000L));

        assertThat(BudgetMath.percentUsed(BudgetLimits.cost(1_000_000, "EUR"), totals))
                .isCloseTo(80.0, within(0.001));
    }

    @Test
    void tightestLimitWinsAndMayExceed100() {
        BudgetLimits both = new BudgetLimits(10_000L, 1_000_000L, "EUR", 80, true);
        UsageTotals totals = new UsageTotals(1, 2_000, 0, 0, Map.of("EUR", 1_500_000L));

        assertThat(BudgetMath.percentUsed(both, totals)).isCloseTo(150.0, within(0.001));
    }

    @Test
    void noUsageIsZeroPercent() {
        assertThat(BudgetMath.percentUsed(BudgetLimits.tokens(1_000), UsageTotals.EMPTY)).isZero();
    }

    @Test
    void workspaceOfTargets() {
        UUID ws = UUID.randomUUID();
        assertThat(BudgetMath.workspaceOf(new BudgetTarget.Global())).isNull();
        assertThat(BudgetMath.workspaceOf(new BudgetTarget.Workspace(ws))).isEqualTo(ws);
        assertThat(BudgetMath.workspaceOf(new BudgetTarget.Agent(ws, UUID.randomUUID()))).isEqualTo(ws);
        assertThat(BudgetMath.workspaceOf(new BudgetTarget.Principal(null, UUID.randomUUID()))).isNull();
    }
}
