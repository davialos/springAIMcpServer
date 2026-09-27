package com.springaimcpservercommon.security.authz.condition;

import java.util.UUID;

/**
 * Evaluates the ABAC conditions of a grant ({@code dai_grant.conditions}, SEC-01 §7 step 4).
 */
public interface GrantConditionEvaluator {

    /**
     * Result of evaluating a grant's conditions.
     */
    enum Result {
        /** All conditions hold. */
        SATISFIED,
        /** At least one condition does not hold. */
        NOT_SATISFIED,
        /** The conditions cannot be parsed or use unknown operators (fail closed). */
        INVALID
    }

    /**
     * Evaluates conditions.
     *
     * @param grantId        grant id (cache key)
     * @param conditionsJson conditions JSON object text
     * @param context        facts
     * @return the result
     */
    Result evaluate(UUID grantId, String conditionsJson, ConditionContext context);

    /**
     * An evaluator that treats every condition as invalid (fail closed) — for hosts that disable ABAC.
     *
     * @return the evaluator
     */
    static GrantConditionEvaluator rejectAll() {
        return (grantId, json, context) -> Result.INVALID;
    }
}
