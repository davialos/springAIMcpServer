package com.springaimcpservercommon.ruleengine.eval;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Policy;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** The results of an evaluation, from one rule up to the answer the caller gets. */
public final class Results {

    private Results() {
    }

    /**
     * One rule's raw result.
     *
     * @param ruleId   rule id
     * @param code     rule code
     * @param name     rule name
     * @param expression the CEL expression that was evaluated
     * @param result   {@code true}/{@code false}, or {@code null} when the rule could not be evaluated
     * @param error    why it could not be evaluated, or {@code null}
     * @param action   the action of the result (the rule's true or false action); {@code ALLOW} on an error
     * @param message  the rule's message for that result in the requested language, or {@code null}
     * @param selected whether the group's policy reports this rule in the final messages
     * @param micros   evaluation time in microseconds
     */
    public record RuleResult(long ruleId, String code, String name, String expression, @Nullable Boolean result,
                             @Nullable String error, Action action, @Nullable String message, boolean selected,
                             long micros) {

        /**
         * A copy with the selection decided by the policy.
         *
         * @param value whether the rule is reported
         * @return the copy
         */
        public RuleResult withSelected(boolean value) {
            return new RuleResult(ruleId, code, name, expression, result, error, action, message, value, micros);
        }

        /**
         * Whether the rule produced a result.
         *
         * @return {@code false} when it failed to evaluate
         */
        public boolean evaluated() {
            return result != null;
        }
    }

    /**
     * A message of the final answer.
     *
     * @param source   {@code RULE} or {@code GROUP}
     * @param group    group code
     * @param rule     rule code, for rule messages
     * @param outcome  the true/false result the message belongs to
     * @param severity the action of that result
     * @param text     the message in the requested language
     */
    public record FinalMessage(String source, String group, @Nullable String rule, boolean outcome, Action severity,
                               String text) { }

    /**
     * One group's result under its policy.
     *
     * @param groupId  group id
     * @param code     group code
     * @param name     group name
     * @param policy   the evaluation policy
     * @param result   the group's result
     * @param action   the action: the strictest of the reported rules' actions and the group's own for its result
     * @param messages the messages the policy reports
     * @param rules    every rule that was evaluated (FIRST_MATCH stops early), with what the policy selected
     */
    public record GroupResult(long groupId, String code, String name, Policy policy, boolean result, Action action,
                              List<FinalMessage> messages, List<RuleResult> rules) { }

    /**
     * A channel dispatch that followed the evaluation.
     *
     * @param channel channel name
     * @param type    EMAIL, PUSH or API
     * @param status  SENT, FAILED or SKIPPED
     * @param detail  what happened
     */
    public record Dispatch(String channel, String type, String status, @Nullable String detail) { }

    /**
     * The answer to an evaluation request.
     *
     * @param evaluationId audit id
     * @param tenant       tenant code
     * @param module       module code
     * @param trigger      what triggered it ({@code FORM:LOAN_APPLICATION/SUBMIT}), or the group codes
     * @param result       all groups' results combined (true when every group is true)
     * @param action       the strictest action of all groups
     * @param allowed      {@code false} when the action is BLOCK
     * @param language     the language of the messages
     * @param messages     the final messages, in group order
     * @param groups       per-group results (detailed view only)
     * @param dispatches   channel dispatches (detailed view only)
     * @param dryRun       nothing was logged or dispatched
     */
    public record EvaluationResponse(UUID evaluationId, String tenant, String module, @Nullable String trigger,
                                     boolean result, Action action, boolean allowed, String language,
                                     List<FinalMessage> messages, @Nullable List<GroupResult> groups,
                                     @Nullable List<Dispatch> dispatches, boolean dryRun) { }
}
