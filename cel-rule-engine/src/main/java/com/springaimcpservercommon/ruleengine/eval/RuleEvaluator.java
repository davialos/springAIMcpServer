package com.springaimcpservercommon.ruleengine.eval;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.OnError;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.eval.Results.RuleResult;
import com.springaimcpservercommon.ruleengine.library.CelEngine;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Evaluates one rule's CEL expression and attaches the message and action of the result. */
@Component
public class RuleEvaluator {

    private final CelEngine cel;

    public RuleEvaluator(CelEngine cel) {
        this.cel = cel;
    }

    /**
     * Evaluates a rule.
     *
     * @param rule      the rule
     * @param variables CEL variables from the caller's (coerced) context
     * @param messages  resolver for the rule's messages
     * @param groupCode the group the rule is evaluated for (placeholder {@code group.code})
     * @param onError   what a rule that cannot be evaluated counts as: {@code AS_FALSE} gives {@code result == false}
     *                  (with the error kept and the rule's false message and action), {@code SKIP} gives
     *                  {@code result == null}
     * @return the raw result
     */
    public RuleResult evaluate(Rule rule, Map<String, Object> variables, MessageResolver messages, String groupCode,
                               OnError onError) {
        long start = System.nanoTime();
        Boolean result = null;
        String error = null;
        try {
            result = cel.evaluate(cel.compile(rule.expression()), variables);
        } catch (CelEngine.ExpressionException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (error != null && onError == OnError.AS_FALSE) {
            result = false; // fail-safe: what cannot be evaluated is not allowed through
        }
        Map<String, String> extra = Map.of("rule.code", rule.code(), "rule.name", rule.name(), "group.code", groupCode);
        Action action = result == null ? Action.ALLOW : result ? rule.trueAction() : rule.falseAction();
        String message = result == null ? null : messages.text(result ? rule.trueBundleId() : rule.falseBundleId(), extra);
        return new RuleResult(rule.id(), rule.code(), rule.name(), rule.expression(), result, error, action, message,
                false, (System.nanoTime() - start) / 1000);
    }
}
