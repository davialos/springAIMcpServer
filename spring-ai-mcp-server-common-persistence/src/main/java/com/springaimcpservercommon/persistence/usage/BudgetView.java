package com.springaimcpservercommon.persistence.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a budget.
 *
 * @param id         budget id
 * @param target     whose usage is limited
 * @param period     period
 * @param limits     limits
 * @param enabled    whether enforced
 * @param createdAt  creation time
 * @param updatedAt  last change time
 * @param rowVersion optimistic-lock version, to be passed back on update
 */
public record BudgetView(
        UUID id,
        BudgetTarget target,
        BudgetPeriod period,
        BudgetLimits limits,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long rowVersion) {
}
