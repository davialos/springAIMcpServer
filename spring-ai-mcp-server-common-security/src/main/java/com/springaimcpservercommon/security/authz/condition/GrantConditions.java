package com.springaimcpservercommon.security.authz.condition;

import java.util.List;

/**
 * The parsed conditions of one grant: all must hold (AND). Alternatives (OR) are expressed as separate grants.
 *
 * @param conditions predicates
 */
public record GrantConditions(List<Condition> conditions) {

    /**
     * Copies the list.
     */
    public GrantConditions {
        conditions = List.copyOf(conditions);
    }

    /**
     * Evaluates all predicates.
     *
     * @param context facts
     * @return {@code true} if every predicate holds (vacuously true when empty)
     */
    public boolean test(ConditionContext context) {
        for (Condition condition : conditions) {
            if (!condition.test(context)) {
                return false;
            }
        }
        return true;
    }
}
