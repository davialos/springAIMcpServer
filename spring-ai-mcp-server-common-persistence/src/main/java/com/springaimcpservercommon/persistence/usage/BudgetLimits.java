package com.springaimcpservercommon.persistence.usage;

import org.jspecify.annotations.Nullable;

/**
 * Limits of a budget (LLD-10 §6). Mirrors {@code ck_budget_limit}, {@code ck_budget_limit_values},
 * {@code ck_budget_currency} and {@code ck_budget_soft_limit}.
 *
 * @param limitTokens     token limit per period (input + cached input + output, see {@link UsageTotals#totalTokens()}), if any
 * @param limitCostMicros cost limit per period in micros of {@code currency}, if any
 * @param currency        ISO-4217 code, exactly when a cost limit is set
 * @param softLimitPct    percentage of a limit that raises an alert (1..100)
 * @param hardLimit       whether reaching a limit rejects new turns (else alert only)
 */
public record BudgetLimits(
        @Nullable Long limitTokens,
        @Nullable Long limitCostMicros,
        @Nullable String currency,
        int softLimitPct,
        boolean hardLimit) {

    /** Default soft limit percentage. */
    public static final int DEFAULT_SOFT_LIMIT_PCT = 80;

    /** Validates the limits. */
    public BudgetLimits {
        if (limitTokens == null && limitCostMicros == null) {
            throw new IllegalArgumentException("a budget needs a token or a cost limit");
        }
        if ((limitTokens != null && limitTokens <= 0) || (limitCostMicros != null && limitCostMicros <= 0)) {
            throw new IllegalArgumentException("budget limits must be positive");
        }
        if ((limitCostMicros == null) != (currency == null)) {
            throw new IllegalArgumentException("a currency is required exactly when a cost limit is set");
        }
        if (currency != null) {
            Currencies.require(currency);
        }
        if (softLimitPct < 1 || softLimitPct > 100) {
            throw new IllegalArgumentException("softLimitPct must be within 1..100");
        }
    }

    /**
     * Token-only hard limit with the default soft limit.
     *
     * @param limitTokens tokens per period
     * @return the limits
     */
    public static BudgetLimits tokens(long limitTokens) {
        return new BudgetLimits(limitTokens, null, null, DEFAULT_SOFT_LIMIT_PCT, true);
    }

    /**
     * Cost-only hard limit with the default soft limit.
     *
     * @param limitCostMicros cost per period in micros
     * @param currency        ISO-4217 code
     * @return the limits
     */
    public static BudgetLimits cost(long limitCostMicros, String currency) {
        return new BudgetLimits(null, limitCostMicros, currency, DEFAULT_SOFT_LIMIT_PCT, true);
    }
}
