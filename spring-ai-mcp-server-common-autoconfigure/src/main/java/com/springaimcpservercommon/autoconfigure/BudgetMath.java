package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.usage.BudgetLimits;
import com.springaimcpservercommon.persistence.usage.BudgetTarget;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Budget arithmetic shared by the budget admin API and the invocation-path budget check. */
@NullMarked
final class BudgetMath {

    private BudgetMath() {
    }

    /**
     * Share of the tightest limit that is used, as a percentage (may exceed 100).
     *
     * @param limits the budget's limits
     * @param totals usage in the budget's current period
     * @return {@code max(tokens used / token limit, cost used / cost limit) * 100}, or 0 when neither applies
     */
    static double percentUsed(BudgetLimits limits, UsageTotals totals) {
        double percent = 0;
        if (limits.limitTokens() != null) {
            percent = Math.max(percent, 100.0 * totals.totalTokens() / limits.limitTokens());
        }
        if (limits.limitCostMicros() != null && limits.currency() != null) {
            percent = Math.max(percent, 100.0 * totals.costMicros(limits.currency()) / limits.limitCostMicros());
        }
        return percent;
    }

    /**
     * Workspace a budget target belongs to.
     *
     * @param target budget target
     * @return the workspace id, or {@code null} for global and cross-workspace principal budgets
     */
    static @Nullable UUID workspaceOf(BudgetTarget target) {
        return switch (target) {
            case BudgetTarget.Global _ -> null;
            case BudgetTarget.Workspace w -> w.workspaceId();
            case BudgetTarget.Agent a -> a.workspaceId();
            case BudgetTarget.Principal p -> p.workspaceId();
        };
    }
}
