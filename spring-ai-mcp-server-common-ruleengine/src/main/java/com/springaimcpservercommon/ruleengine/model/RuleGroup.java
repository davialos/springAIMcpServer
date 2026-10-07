package com.springaimcpservercommon.ruleengine.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * An active rule group with its evaluation policy.
 *
 * @param id                      group id
 * @param organizationId          organization the group belongs to, or {@code null} = every organization of the tenant
 * @param moduleCode              module code
 * @param code                    group code
 * @param name                    display name
 * @param policy                  evaluation policy
 * @param matchOn                 what FIRST_MATCH / ALL_MATCH select on
 * @param compositeTrueMessage    COMPOSITE: message when every rule is true
 * @param compositeFalseMessage   COMPOSITE: message when any rule is false
 * @param compositeTrueAction     COMPOSITE: action when every rule is true
 * @param compositeFalseAction    COMPOSITE: action when any rule is false
 * @param onError                 action for a rule that could not be evaluated
 * @param rules                   enabled member rules, ordered by sequence
 */
public record RuleGroup(UUID id, @Nullable UUID organizationId, String moduleCode, String code, String name,
                        EvaluationPolicy policy, MatchOn matchOn, @Nullable UUID compositeTrueMessage,
                        @Nullable UUID compositeFalseMessage, Action compositeTrueAction,
                        Action compositeFalseAction, Action onError, List<GroupRule> rules) {

    /** Defensive copy. */
    public RuleGroup {
        rules = List.copyOf(rules);
    }
}
