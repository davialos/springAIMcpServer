package com.springaimcpservercommon.ruleengine.cache;

import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * A rule with its compiled expression, or the reason it does not compile (a rule that no longer type-checks after a
 * parameter change evaluates to ERROR/COMPILE_ERROR instead of breaking the group).
 *
 * @param rule         the rule
 * @param expression   compiled expression, or {@code null} if compilation failed
 * @param compileError compiler message, or {@code null} on success
 */
public record CompiledRule(Rule rule, @Nullable CompiledExpression expression, @Nullable String compileError) {
}
