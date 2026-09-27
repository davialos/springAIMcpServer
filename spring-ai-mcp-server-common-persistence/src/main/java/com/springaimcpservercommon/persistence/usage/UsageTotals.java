package com.springaimcpservercommon.persistence.usage;

import java.util.Map;
import java.util.Objects;

/**
 * Summed usage over a window.
 *
 * @param calls                model calls
 * @param inputTokens          non-cached input tokens
 * @param outputTokens         output tokens
 * @param cachedInputTokens    cached input tokens
 * @param costMicrosByCurrency priced cost per ISO-4217 currency (unpriced usage has no entry)
 */
public record UsageTotals(
        long calls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        Map<String, Long> costMicrosByCurrency) {

    /** Totals of no usage. */
    public static final UsageTotals EMPTY = new UsageTotals(0, 0, 0, 0, Map.of());

    /** Copies the cost map. */
    public UsageTotals {
        costMicrosByCurrency = Map.copyOf(Objects.requireNonNull(costMicrosByCurrency, "costMicrosByCurrency"));
    }

    /**
     * Tokens counted against a token budget: input + cached input + output.
     *
     * @return total tokens
     */
    public long totalTokens() {
        return Math.addExact(Math.addExact(inputTokens, cachedInputTokens), outputTokens);
    }

    /**
     * Cost in one currency.
     *
     * @param currency ISO-4217 code
     * @return cost in micros, 0 when there is none
     */
    public long costMicros(String currency) {
        return costMicrosByCurrency.getOrDefault(currency, 0L);
    }
}
