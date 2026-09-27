package com.springaimcpservercommon.persistence.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Budget period (matches {@code ck_budget_period}); periods are calendar days/months in UTC. */
public enum BudgetPeriod {
    /** UTC calendar day. */
    DAY,
    /** UTC calendar month. */
    MONTH;

    /**
     * The period window containing an instant.
     *
     * @param at instant
     * @return half-open window [from, to)
     */
    public UsageWindow windowContaining(Instant at) {
        Objects.requireNonNull(at, "at");
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        LocalDate start = this == DAY ? day : day.withDayOfMonth(1);
        LocalDate end = this == DAY ? start.plusDays(1) : start.plusMonths(1);
        return new UsageWindow(start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
