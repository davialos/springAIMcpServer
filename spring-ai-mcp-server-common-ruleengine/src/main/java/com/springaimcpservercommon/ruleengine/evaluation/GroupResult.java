package com.springaimcpservercommon.ruleengine.evaluation;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;

import java.util.List;

/**
 * The raw result of evaluating a rule group under its policy.
 *
 * @param group     the group
 * @param evaluated every rule that actually ran, in sequence order (FIRST_MATCH may stop early)
 * @param selected  the results the policy reports (see {@link com.springaimcpservercommon.ruleengine.model.EvaluationPolicy})
 * @param matched   FIRST_MATCH / ALL_MATCH: at least one rule matched; EVALUATE_ALL: always true;
 *                  COMPOSITE: every rule was true
 * @param decision  the action for the transaction: strictest of the selected results, and of any evaluation error
 */
public record GroupResult(RuleGroup group, List<RuleResult> evaluated, List<RuleResult> selected, boolean matched,
                          Action decision) {

    /** Defensive copies. */
    public GroupResult {
        evaluated = List.copyOf(evaluated);
        selected = List.copyOf(selected);
    }
}
