package com.springaimcpservercommon.ruleengine.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * An active rule: a CEL boolean expression plus what to say and do per outcome.
 *
 * @param id               rule id
 * @param code             rule code (unique per tenant/organization/module)
 * @param name             display name
 * @param expression       CEL expression over library parameters
 * @param trueMessage      bundle shown when true, or {@code null} for silence
 * @param falseMessage     bundle shown when false, or {@code null}
 * @param trueAction       action when true
 * @param falseAction      action when false
 */
public record Rule(UUID id, String code, String name, String expression, @Nullable UUID trueMessage,
                   @Nullable UUID falseMessage, Action trueAction, Action falseAction) {
}
