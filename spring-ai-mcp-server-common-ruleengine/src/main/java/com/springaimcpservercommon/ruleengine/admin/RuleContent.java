package com.springaimcpservercommon.ruleengine.admin;

import com.springaimcpservercommon.ruleengine.model.Action;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The editable definition of a rule (what a revision holds).
 *
 * @param name                 display name (1..200 characters)
 * @param description          free text
 * @param expression           CEL boolean expression over library parameters
 * @param trueMessageBundleId  message when true, or {@code null} for silence
 * @param falseMessageBundleId message when false, or {@code null}
 * @param trueAction           action when true
 * @param falseAction          action when false
 */
public record RuleContent(String name, @Nullable String description, String expression,
                          @Nullable UUID trueMessageBundleId, @Nullable UUID falseMessageBundleId,
                          Action trueAction, Action falseAction) {
}
