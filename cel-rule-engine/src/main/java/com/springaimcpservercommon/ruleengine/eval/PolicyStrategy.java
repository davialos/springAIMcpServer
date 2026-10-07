package com.springaimcpservercommon.ruleengine.eval;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.CompositeMode;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Policy;
import com.springaimcpservercommon.ruleengine.eval.Results.FinalMessage;
import com.springaimcpservercommon.ruleengine.eval.Results.GroupResult;
import com.springaimcpservercommon.ruleengine.eval.Results.RuleResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * How the rules of a group are evaluated and what is reported. A strategy decides <em>which rules run</em> (FIRST_MATCH
 * stops early) and, from their results, <em>the group's result and which rules are reported</em>; the messages and the
 * action are then assembled the same way for every policy: the reported rules' messages in sequence, then the group's
 * own message for its result, and the strictest of the reported rules' actions and the group's action for its result.
 */
public interface PolicyStrategy {

    /**
     * What a strategy works on.
     *
     * @param group    the group
     * @param rules    its active rules in sequence
     * @param run      evaluates one rule (already applying the group's {@code onError})
     * @param messages the message resolver of this evaluation
     */
    record Input(RuleGroup group, List<Rule> rules, Function<Rule, RuleResult> run, MessageResolver messages) { }

    /**
     * Evaluates a group.
     *
     * @param in the group, its rules and the evaluator
     * @return the group's result
     */
    GroupResult evaluate(Input in);

    /**
     * The strategy of a policy.
     *
     * @param policy the policy
     * @return its strategy
     */
    static PolicyStrategy of(Policy policy) {
        return switch (policy) {
            case FIRST_MATCH -> FIRST_MATCH;
            case ALL_MATCH -> ALL_MATCH;
            case EVALUATE_ALL -> EVALUATE_ALL;
            case COMPOSITE -> COMPOSITE;
        };
    }

    /** The first rule (by sequence) whose result equals {@code matchOn} is the answer; later rules are not run. */
    PolicyStrategy FIRST_MATCH = in -> {
        List<RuleResult> done = new ArrayList<>();
        boolean matchOn = in.group().matchOnTrue();
        boolean matched = false;
        for (Rule rule : in.rules()) {
            RuleResult rr = in.run().apply(rule);
            if (rr.evaluated() && rr.result() == matchOn) {
                rr = rr.withSelected(true);
                matched = true;
                done.add(rr);
                break;
            }
            done.add(rr);
        }
        return Assembler.assemble(in, matched ? matchOn : !matchOn, done);
    };

    /** Every rule runs; every rule whose result equals {@code matchOn} is reported. */
    PolicyStrategy ALL_MATCH = in -> {
        boolean matchOn = in.group().matchOnTrue();
        List<RuleResult> all = runAll(in);
        List<RuleResult> marked = all.stream().map(r -> r.withSelected(r.evaluated() && r.result() == matchOn)).toList();
        boolean matched = marked.stream().anyMatch(RuleResult::selected);
        return Assembler.assemble(in, matched ? matchOn : !matchOn, marked);
    };

    /** Every rule runs; every rule is reported with its true or false message; the group is true when all are. */
    PolicyStrategy EVALUATE_ALL = in -> {
        List<RuleResult> all = runAll(in).stream().map(r -> r.withSelected(r.evaluated())).toList();
        boolean allTrue = all.stream().filter(RuleResult::evaluated).allMatch(RuleResult::result);
        return Assembler.assemble(in, allTrue, all);
    };

    /**
     * Every rule runs and the rules are combined into one result. True: the group's own true message is the answer.
     * False: the failing rules' messages, then the group's false message.
     */
    PolicyStrategy COMPOSITE = in -> {
        List<RuleResult> all = runAll(in);
        boolean any = in.group().compositeMode() == CompositeMode.ANY_TRUE;
        List<RuleResult> evaluated = all.stream().filter(RuleResult::evaluated).toList();
        boolean result = any ? evaluated.stream().anyMatch(RuleResult::result)
                : evaluated.stream().allMatch(RuleResult::result);
        List<RuleResult> marked = all.stream()
                .map(r -> r.withSelected(!result && r.evaluated() && !r.result())).toList();
        return Assembler.assemble(in, result, marked);
    };

    private static List<RuleResult> runAll(Input in) {
        return in.rules().stream().map(r -> in.run().apply(r)).toList();
    }

    /** Builds the {@link GroupResult}: messages and action from the reported rules and the group's own settings. */
    final class Assembler {

        private Assembler() {
        }

        static GroupResult assemble(Input in, boolean result, List<RuleResult> results) {
            RuleGroup g = in.group();
            List<FinalMessage> messages = new ArrayList<>();
            Action action = result ? g.trueAction() : g.falseAction();
            for (RuleResult r : results) {
                if (r.selected()) {
                    action = action.max(r.action());
                    if (r.message() != null) {
                        messages.add(new FinalMessage("RULE", g.code(), r.code(), r.result(), r.action(), r.message()));
                    }
                }
            }
            String own = in.messages().text(result ? g.trueBundleId() : g.falseBundleId(),
                    Map.of("group.code", g.code(), "group.name", g.name()));
            if (own != null) {
                messages.add(new FinalMessage("GROUP", g.code(), null, result, result ? g.trueAction() : g.falseAction(),
                        own));
            }
            return new GroupResult(g.id(), g.code(), g.name(), g.policy(), result, action, messages, results);
        }
    }
}
