package com.springaimcpservercommon.ruleengine.evaluation;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The raw result of evaluating one rule. Never contains input values.
 *
 * @param ruleId        rule id
 * @param ruleCode      rule code
 * @param ruleName      rule display name
 * @param sequence      position inside the group
 * @param outcome       TRUE, FALSE or ERROR
 * @param action        the action this result asks for (rule true/false action, or the group's on-error action)
 * @param messageBundle bundle to show for this result, or {@code null} for silence (always {@code null} for ERROR)
 * @param errorCode     for ERROR: COMPILE_ERROR, MISSING_PARAMETER, INVALID_PARAMETER, NOT_BOOLEAN, EVALUATION_ERROR
 * @param errorDetail   for ERROR: names the problem (parameter or compiler issue), never a value
 */
public record RuleResult(UUID ruleId, String ruleCode, String ruleName, int sequence, Outcome outcome, Action action,
                         @Nullable UUID messageBundle, @Nullable String errorCode, @Nullable String errorDetail) {
}
