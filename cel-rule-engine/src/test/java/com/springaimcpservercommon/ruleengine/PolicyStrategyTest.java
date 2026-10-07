package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.CompositeMode;
import com.springaimcpservercommon.ruleengine.domain.Enums.OnError;
import com.springaimcpservercommon.ruleengine.domain.Enums.Status;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Policy;
import com.springaimcpservercommon.ruleengine.eval.MessageResolver;
import com.springaimcpservercommon.ruleengine.eval.PolicyStrategy;
import com.springaimcpservercommon.ruleengine.eval.Results.GroupResult;
import com.springaimcpservercommon.ruleengine.eval.Results.RuleResult;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What each evaluation policy runs, reports and answers, on fixed rule results. */
class PolicyStrategyTest {

    // bundle 1/2/3: group true / group false / unused; rule messages are "<code> true" / "<code> false"
    private static final BundleRepository BUNDLES = new BundleRepository(null) {
        @Override
        public Map<Long, Map<String, String>> texts(java.util.Collection<Long> ids) {
            Map<Long, Map<String, String>> out = new java.util.HashMap<>();
            ids.forEach(id -> out.put(id, Map.of("en", "bundle " + id + " {x.y}")));
            return out;
        }
    };

    private static Rule rule(long id, String code) {
        // bundle ids: rule N true = N*10+1, false = N*10+2
        return new Rule(id, 1, null, 1, code, code, null, "x", id * 10 + 1, id * 10 + 2, Action.ALLOW, Action.BLOCK,
                Status.ACTIVE, 1);
    }

    private static RuleGroup group(Policy policy, boolean matchOnTrue, CompositeMode mode) {
        return new RuleGroup(1, 1, null, 1, "G", "G", null, policy, matchOnTrue, mode, OnError.AS_FALSE, 1L, 2L,
                Action.ALLOW, Action.WARN, Status.ACTIVE, 1);
    }

    private static GroupResult run(RuleGroup g, Map<String, Boolean> results, List<String> executed) {
        MessageResolver resolver = new MessageResolver(BUNDLES, List.of("en"), Map.of("x.y", "Y"));
        List<Rule> rules = new ArrayList<>();
        long id = 1;
        for (String code : results.keySet()) {
            rules.add(rule(id++, code));
        }
        return PolicyStrategy.of(g.policy()).evaluate(new PolicyStrategy.Input(g, rules, r -> {
            executed.add(r.code());
            boolean v = results.get(r.code());
            return new RuleResult(r.id(), r.code(), r.name(), r.expression(), v, null,
                    v ? r.trueAction() : r.falseAction(), resolver.text(v ? r.trueBundleId() : r.falseBundleId(), Map.of()),
                    false, 1);
        }, resolver));
    }

    private static Map<String, Boolean> ordered(Object... kv) {
        Map<String, Boolean> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Boolean) kv[i + 1]);
        }
        return m;
    }

    private static List<String> texts(GroupResult r) {
        return r.messages().stream().map(m -> m.text()).toList();
    }

    @Test
    void firstMatchOnFalseStopsAtTheFirstFailureAndSkipsTheRest() {
        List<String> executed = new ArrayList<>();
        GroupResult r = run(group(Policy.FIRST_MATCH, false, CompositeMode.ALL_TRUE),
                ordered("A", true, "B", false, "C", false), executed);
        assertThat(executed).containsExactly("A", "B");
        assertThat(r.result()).isFalse();
        assertThat(r.action()).isEqualTo(Action.BLOCK);
        assertThat(texts(r)).containsExactly("bundle 22 Y", "bundle 2 Y");
    }

    @Test
    void firstMatchOnTrueReturnsTheFirstTrueRule() {
        List<String> executed = new ArrayList<>();
        GroupResult r = run(group(Policy.FIRST_MATCH, true, CompositeMode.ALL_TRUE),
                ordered("A", false, "B", true, "C", true), executed);
        assertThat(executed).containsExactly("A", "B");
        assertThat(r.result()).isTrue();
        assertThat(texts(r)).containsExactly("bundle 21 Y", "bundle 1 Y");
    }

    @Test
    void firstMatchWithoutAMatchAnswersTheOpposite() {
        GroupResult r = run(group(Policy.FIRST_MATCH, false, CompositeMode.ALL_TRUE),
                ordered("A", true, "B", true), new ArrayList<>());
        assertThat(r.result()).as("nothing failed: the group is true").isTrue();
        assertThat(texts(r)).containsExactly("bundle 1 Y");
        assertThat(r.rules()).noneMatch(RuleResult::selected);
    }

    @Test
    void allMatchReportsEveryRuleOfTheTargetResult() {
        GroupResult r = run(group(Policy.ALL_MATCH, true, CompositeMode.ALL_TRUE),
                ordered("A", true, "B", false, "C", true), new ArrayList<>());
        assertThat(r.rules()).hasSize(3).filteredOn(RuleResult::selected).extracting(RuleResult::code).containsExactly("A", "C");
        assertThat(r.result()).isTrue();
        assertThat(texts(r)).containsExactly("bundle 11 Y", "bundle 31 Y", "bundle 1 Y");
        GroupResult none = run(group(Policy.ALL_MATCH, true, CompositeMode.ALL_TRUE), ordered("A", false), new ArrayList<>());
        assertThat(none.result()).isFalse();
        assertThat(none.action()).isEqualTo(Action.WARN);
        assertThat(texts(none)).containsExactly("bundle 2 Y");
    }

    @Test
    void evaluateAllReportsTrueAndFalseMessagesAndAndsTheResults() {
        GroupResult r = run(group(Policy.EVALUATE_ALL, true, CompositeMode.ALL_TRUE),
                ordered("A", true, "B", false), new ArrayList<>());
        assertThat(r.result()).isFalse();
        assertThat(texts(r)).containsExactly("bundle 11 Y", "bundle 22 Y", "bundle 2 Y");
        assertThat(r.action()).isEqualTo(Action.BLOCK);
        assertThat(run(group(Policy.EVALUATE_ALL, true, CompositeMode.ALL_TRUE), ordered("A", true), new ArrayList<>()).result())
                .isTrue();
    }

    @Test
    void compositeIsOneResultWithTheGroupMessageOrTheFailingRules() {
        GroupResult ok = run(group(Policy.COMPOSITE, true, CompositeMode.ALL_TRUE), ordered("A", true, "B", true), new ArrayList<>());
        assertThat(ok.result()).isTrue();
        assertThat(texts(ok)).as("only the composite message").containsExactly("bundle 1 Y");
        assertThat(ok.action()).isEqualTo(Action.ALLOW);

        GroupResult bad = run(group(Policy.COMPOSITE, true, CompositeMode.ALL_TRUE),
                ordered("A", true, "B", false, "C", false), new ArrayList<>());
        assertThat(bad.result()).isFalse();
        assertThat(texts(bad)).containsExactly("bundle 22 Y", "bundle 32 Y", "bundle 2 Y");
        assertThat(bad.action()).isEqualTo(Action.BLOCK);
    }

    @Test
    void compositeAnyTrueNeedsOneTrueRule() {
        RuleGroup any = group(Policy.COMPOSITE, true, CompositeMode.ANY_TRUE);
        assertThat(run(any, ordered("A", false, "B", true), new ArrayList<>()).result()).isTrue();
        GroupResult none = run(any, ordered("A", false, "B", false), new ArrayList<>());
        assertThat(none.result()).isFalse();
        assertThat(texts(none)).containsExactly("bundle 12 Y", "bundle 22 Y", "bundle 2 Y");
    }

    @Test
    void messagesFallBackAcrossLanguagesAndKeepUnknownPlaceholdersVisible() {
        BundleRepository twoLanguages = new BundleRepository(null) {
            @Override
            public Map<Long, Map<String, String>> texts(java.util.Collection<Long> ids) {
                return Map.of(7L, Map.of("hi", "नमस्ते {customer.name} {customer.missing}", "en", "hello"));
            }
        };
        assertThat(new MessageResolver(twoLanguages, List.of("hi", "en"), Map.of("customer.name", "Asha")).text(7L, Map.of()))
                .isEqualTo("नमस्ते Asha {customer.missing}");
        assertThat(new MessageResolver(twoLanguages, List.of("th", "en"), Map.of()).text(7L, Map.of())).isEqualTo("hello");
        assertThat(new MessageResolver(twoLanguages, List.of("en"), Map.of()).text(null, Map.of())).isNull();
    }
}
