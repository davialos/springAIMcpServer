package com.springaimcpservercommon.ruleengine.response;

import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the calling application receives for one rule group.
 *
 * <p>The number of messages follows the policy: FIRST_MATCH at most one; COMPOSITE the group message followed by the
 * failing rules' messages; ALL_MATCH the matching rules'; EVALUATE_ALL every rule's (true and false).
 *
 * @param moduleCode     module
 * @param groupCode      group
 * @param policy         the policy used
 * @param decision       ALLOW, WARN or BLOCK for the transaction
 * @param matched        see {@link com.springaimcpservercommon.ruleengine.evaluation.GroupResult#matched()}
 * @param primaryMessage the one message to show if the caller shows only one (the most severe; composite: the group message)
 * @param messages       all messages the policy reports, in order
 * @param results        raw per-rule results; empty unless {@link ResponseDetail#WITH_RAW} was requested
 */
public record EvaluationResponse(String moduleCode, String groupCode, EvaluationPolicy policy, Action decision,
                                 boolean matched, @Nullable ResponseMessage primaryMessage,
                                 List<ResponseMessage> messages, List<RuleResult> results) {

    /** Defensive copies. */
    public EvaluationResponse {
        messages = List.copyOf(messages);
        results = List.copyOf(results);
    }
}
