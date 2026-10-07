package com.springaimcpservercommon.validation;

import org.jspecify.annotations.Nullable;

/**
 * Changes the order of a rule (or skips it) in contexts matching a scope, without touching the rule itself. When
 * several overrides match a rule, the most specific scope wins, then the one registered last.
 *
 * @param rulePattern rule id pattern ({@code *} wildcard)
 * @param scope       contexts it applies to
 * @param order       the order to use in those contexts, or {@code null} when {@code skip}
 * @param skip        true to not run the rule at all in those contexts
 */
public record OrderOverride(String rulePattern, Scope scope, @Nullable Integer order, boolean skip) {

    /**
     * Runs the rule(s) at another position in the given contexts.
     *
     * @param rulePattern rule id pattern
     * @param scope       contexts
     * @param order       new order
     * @return the override
     */
    public static OrderOverride reorder(String rulePattern, Scope scope, int order) {
        return new OrderOverride(rulePattern, scope, order, false);
    }

    /**
     * Turns the rule(s) off in the given contexts.
     *
     * @param rulePattern rule id pattern
     * @param scope       contexts
     * @return the override
     */
    public static OrderOverride skip(String rulePattern, Scope scope) {
        return new OrderOverride(rulePattern, scope, null, true);
    }
}
