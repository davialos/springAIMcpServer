package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.cache.MessageCatalog;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.response.ResponseDetail;
import com.springaimcpservercommon.ruleengine.store.TenantData;
import com.springaimcpservercommon.ruleengine.support.InMemoryRuleStore;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeCasesTest {

    private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Map<String, Object> facts() {
        return Map.of("customer", Map.of("age", 34, "kycStatus", "VERIFIED", "creditScore", 720, "email", "a@b.c"),
                "loan", Map.of("amount", 1000.0));
    }

    @Test
    void anOrganizationGroupShadowsTheTenantWideGroupOfTheSameCode() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        TenantData base = store.tenant;
        RuleGroup tenantWide = base.groups().getFirst();
        RuleGroup orgSpecific = new RuleGroup(UUID.randomUUID(), ORG, tenantWide.moduleCode(), tenantWide.code(),
                "org override", tenantWide.policy(), tenantWide.matchOn(), tenantWide.compositeTrueMessage(),
                tenantWide.compositeFalseMessage(), Action.WARN, Action.WARN, Action.BLOCK, tenantWide.rules());
        List<RuleGroup> groups = new ArrayList<>(base.groups());
        groups.add(orgSpecific);
        store.tenant = new TenantData(base.tenantId(), groups, base.triggers(), base.channels(), base.emailTemplates(),
                base.apiEndpoints());
        RuleEngine engine = new RuleEngine(new RuleCatalogCache(store, Clock.systemUTC(), Duration.ofSeconds(30), 5, "en"),
                null, EvaluationRecorder.NONE);

        var inOrg = engine.evaluate(new EvaluationRequest(SampleTenant.TENANT, ORG, "LOAN", tenantWide.code(), facts(),
                List.of())).response();
        var otherOrg = engine.evaluate(new EvaluationRequest(SampleTenant.TENANT, UUID.randomUUID(), "LOAN",
                tenantWide.code(), facts(), List.of())).response();

        assertThat(inOrg.decision()).as("org override's composite-true action is WARN").isEqualTo(Action.WARN);
        assertThat(otherOrg.decision()).isEqualTo(Action.ALLOW);
    }

    @Test
    void anExpressionThatDoesNotCompileIsAnErrorForThatRuleOnly() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        TenantData base = store.tenant;
        RuleGroup g = base.groups().stream().filter(x -> x.code().equals("LOAN_FULL_REPORT")).findFirst().orElseThrow();
        var broken = new com.springaimcpservercommon.ruleengine.model.GroupRule(
                new com.springaimcpservercommon.ruleengine.model.Rule(UUID.randomUUID(), "BROKEN", "broken",
                        "customer.nope > 1", null, null, Action.ALLOW, Action.ALLOW), 5);
        List<com.springaimcpservercommon.ruleengine.model.GroupRule> members = new ArrayList<>(g.rules());
        members.add(0, broken);
        RuleGroup patched = new RuleGroup(g.id(), null, g.moduleCode(), g.code(), g.name(), g.policy(), g.matchOn(),
                null, null, Action.ALLOW, Action.BLOCK, Action.WARN, members);
        store.tenant = new TenantData(base.tenantId(), List.of(patched), List.of(), List.of(), Map.of(), Map.of());
        RuleEngine engine = new RuleEngine(new RuleCatalogCache(store, Clock.systemUTC(), Duration.ofSeconds(30), 5, "en"),
                null, EvaluationRecorder.NONE);

        var r = engine.evaluate(new EvaluationRequest(SampleTenant.TENANT, null, "LOAN", "LOAN_FULL_REPORT", facts(),
                List.of()), ResponseDetail.WITH_RAW, false).response();

        assertThat(r.results().getFirst().errorCode()).isEqualTo("COMPILE_ERROR");
        assertThat(r.results().subList(1, r.results().size())).noneMatch(x -> x.outcome() == Outcome.ERROR);
        assertThat(r.decision()).as("group on_error=WARN applies, the good rules still ran").isEqualTo(Action.WARN);
    }

    @Test
    void aBundleWithoutTheRequestedLanguageFallsBackToTheFirstAvailable() {
        UUID id = UUID.randomUUID();
        var catalog = new MessageCatalog(Map.of(id, Map.of("th", "ไทย", "hi", "हिन्दी")), "en");

        assertThat(catalog.resolve(id, List.of("fr")).language()).as("no en either: alphabetical first").isEqualTo("hi");
        assertThat(catalog.resolve(null, List.of("en"))).isNull();
        assertThat(catalog.resolve(UUID.randomUUID(), List.of("en"))).isNull();
        assertThat(OwnerType.valueOf("RULE")).isNotNull();
    }
}
