package com.springaimcpservercommon.ruleengine.evaluation;

import com.springaimcpservercommon.ruleengine.cache.CompiledRule;
import com.springaimcpservercommon.ruleengine.cache.TenantCatalog;
import com.springaimcpservercommon.ruleengine.cel.Facts;
import com.springaimcpservercommon.ruleengine.cel.FactException;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.GroupRule;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.model.Rule;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import dev.cel.runtime.CelEvaluationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a rule group's rules under the group's policy:
 *
 * <ul>
 *   <li><b>FIRST_MATCH</b> — rules run in sequence order and evaluation stops at the first whose result equals
 *       {@code matchOn}; only that rule is reported. {@code matchOn=TRUE}: "the first rule that holds";
 *       {@code matchOn=FALSE}: "the first rule that fails".</li>
 *   <li><b>ALL_MATCH</b> — every rule runs; all whose result equals {@code matchOn} are reported.</li>
 *   <li><b>EVALUATE_ALL</b> — every rule runs; every result, true and false, is reported.</li>
 *   <li><b>COMPOSITE</b> — every rule runs; all must be true. The failing rules are reported (the group message is
 *       added by the response composer).</li>
 * </ul>
 *
 * <p>A rule that cannot be evaluated yields {@link Outcome#ERROR} with the group's on-error action. An error counts as
 * "not true": it matches {@code matchOn=FALSE} and fails a COMPOSITE group. Under every policy an error also
 * contributes its action to the decision, so an unevaluable rule is never silently skipped (fail closed by default).
 * Stateless and thread-safe.
 */
public final class GroupEvaluator {

    /**
     * Evaluates the group.
     *
     * @param catalog the tenant snapshot the group came from (provides compiled rules)
     * @param group   the group
     * @param facts   the caller's values (one instance per evaluation)
     * @return the raw result
     */
    public GroupResult evaluate(TenantCatalog catalog, RuleGroup group, Facts facts) {
        List<RuleResult> evaluated = new ArrayList<>();
        List<RuleResult> selected = new ArrayList<>();
        boolean matched;
        switch (group.policy()) {
            case FIRST_MATCH -> {
                for (GroupRule member : group.rules()) {
                    RuleResult r = run(catalog, group, member, facts);
                    evaluated.add(r);
                    if (matches(r, group.matchOn())) {
                        selected.add(r);
                        break;
                    }
                }
                matched = !selected.isEmpty();
            }
            case ALL_MATCH -> {
                runAll(catalog, group, facts, evaluated);
                evaluated.stream().filter(r -> matches(r, group.matchOn())).forEach(selected::add);
                matched = !selected.isEmpty();
            }
            case EVALUATE_ALL -> {
                runAll(catalog, group, facts, evaluated);
                selected.addAll(evaluated);
                matched = true;
            }
            case COMPOSITE -> {
                runAll(catalog, group, facts, evaluated);
                evaluated.stream().filter(r -> r.outcome() != Outcome.TRUE).forEach(selected::add);
                matched = selected.isEmpty();
            }
            default -> throw new IllegalStateException("unknown policy " + group.policy());
        }
        return new GroupResult(group, evaluated, selected, matched, decide(group, evaluated, selected, matched));
    }

    private static Action decide(RuleGroup group, List<RuleResult> evaluated, List<RuleResult> selected,
                                 boolean matched) {
        Action decision = Action.ALLOW;
        switch (group.policy()) {
            case COMPOSITE -> decision = matched ? group.compositeTrueAction() : group.compositeFalseAction();
            default -> {
                for (RuleResult r : selected) {
                    decision = Action.strictest(decision, r.action());
                }
            }
        }
        for (RuleResult r : evaluated) {
            if (r.outcome() == Outcome.ERROR) {
                decision = Action.strictest(decision, r.action());
            }
        }
        return decision;
    }

    private void runAll(TenantCatalog catalog, RuleGroup group, Facts facts, List<RuleResult> out) {
        for (GroupRule member : group.rules()) {
            out.add(run(catalog, group, member, facts));
        }
    }

    private static boolean matches(RuleResult r, MatchOn matchOn) {
        return matchOn == MatchOn.TRUE ? r.outcome() == Outcome.TRUE : r.outcome() != Outcome.TRUE;
    }

    private RuleResult run(TenantCatalog catalog, RuleGroup group, GroupRule member, Facts facts) {
        Rule rule = member.rule();
        CompiledRule compiled = catalog.compiled(rule);
        if (compiled.expression() == null) {
            return error(group, member, "COMPILE_ERROR", compiled.compileError());
        }
        Map<String, Object> bindings = new HashMap<>();
        try {
            for (Parameter p : compiled.expression().referenced()) {
                bindings.put(p.celName(), facts.bind(p));
            }
        } catch (FactException e) {
            return error(group, member, e.code(), e.getMessage());
        }
        try {
            Object value = compiled.expression().program().eval(bindings);
            if (value instanceof Boolean b) {
                return b
                        ? new RuleResult(rule.id(), rule.code(), rule.name(), member.sequence(), Outcome.TRUE,
                                rule.trueAction(), rule.trueMessage(), null, null)
                        : new RuleResult(rule.id(), rule.code(), rule.name(), member.sequence(), Outcome.FALSE,
                                rule.falseAction(), rule.falseMessage(), null, null);
            }
            return error(group, member, "NOT_BOOLEAN", "expression did not return a boolean");
        } catch (CelEvaluationException | RuntimeException e) {
            // the CEL message is dropped on purpose: it could echo input values, which are never logged or returned
            return error(group, member, "EVALUATION_ERROR", "CEL evaluation failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static RuleResult error(RuleGroup group, GroupRule member, String code, String detail) {
        Rule rule = member.rule();
        return new RuleResult(rule.id(), rule.code(), rule.name(), member.sequence(), Outcome.ERROR, group.onError(),
                null, code, detail);
    }
}
