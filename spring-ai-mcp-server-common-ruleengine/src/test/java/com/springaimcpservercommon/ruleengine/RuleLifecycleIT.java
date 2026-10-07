package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.admin.AdminException;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Conflict;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Invalid;
import com.springaimcpservercommon.ruleengine.admin.AdminException.NotFound;
import com.springaimcpservercommon.ruleengine.admin.GroupContent;
import com.springaimcpservercommon.ruleengine.admin.Revision;
import com.springaimcpservercommon.ruleengine.admin.Revision.Kind;
import com.springaimcpservercommon.ruleengine.admin.RuleContent;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.support.RuleEngineTestDatabase;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Draft → review → publish → rollback of rules and groups, on PostgreSQL, ending in what the engine really evaluates. */
class RuleLifecycleIT {

    private static final UUID OK_MSG = SampleTenant.id("cccccccc", 1);
    private static final UUID FAIL_MSG = SampleTenant.id("cccccccc", 2);

    private static RuleEngineTestDatabase db;
    private static JdbcRuleStore store;
    private static RuleLifecycle lifecycle;
    private static RuleLifecycle noReview;

    private final UUID tenant = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        db = RuleEngineTestDatabase.create(true);
        store = new JdbcRuleStore(db.dataSource(), db.schema());
        ParameterLibrary library = new ParameterLibrary(store.loadParameters().value());
        lifecycle = new RuleLifecycle(db.dataSource(), db.schema(), () -> library, true);
        noReview = new RuleLifecycle(db.dataSource(), db.schema(), () -> library, false);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static RuleContent rule(String expression) {
        return new RuleContent("Adult check", "customer must be an adult", expression, OK_MSG, FAIL_MSG, Action.ALLOW,
                Action.BLOCK);
    }

    private UUID newRule(String code, String expression) {
        return lifecycle.createRule(tenant, null, "LOAN", code, rule(expression), "alice").subjectId();
    }

    private UUID publishRule(String code, String expression) {
        UUID id = newRule(code, expression);
        lifecycle.submit(Kind.RULE, tenant, id, "alice");
        lifecycle.approve(Kind.RULE, tenant, id, "bob", "ok");
        lifecycle.publish(Kind.RULE, tenant, id, "carol");
        return id;
    }

    private List<String> liveRuleExpressions() {
        return store.loadTenant(tenant).value().groups().stream().flatMap(g -> g.rules().stream())
                .map(r -> r.rule().expression()).toList();
    }

    private EvaluationResult evaluate(String groupCode, int age) {
        var cache = new RuleCatalogCache(store, Clock.systemUTC(), Duration.ofSeconds(1), 10, "en");
        return new RuleEngine(cache, null, EvaluationRecorder.NONE).evaluate(new EvaluationRequest(tenant, null, "LOAN",
                groupCode, Map.of("customer", Map.of("age", age)), List.of("en")));
    }

    @Test
    void aNewRuleIsADraftTheEngineCannotSee() {
        UUID id = newRule("DRAFT_ONLY", "customer.age >= 18");

        assertThat(lifecycle.rule(tenant, id).summary().status()).isEqualTo("DRAFT");
        assertThat(lifecycle.rule(tenant, id).open().state()).isEqualTo("DRAFT");
        assertThat(store.loadTenant(tenant).value().groups()).isEmpty();
        assertThat(db.queryLong("SELECT count(*) FROM dai_re_rule WHERE id = '" + id + "' AND status = 'DRAFT'")).isEqualTo(1);
    }

    @Test
    void anExpressionThatDoesNotCompileIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> lifecycle.createRule(tenant, null, "LOAN", "BAD_PARAM", rule("customer.shoeSize > 3"), "alice"))
                .isInstanceOfSatisfying(Invalid.class, e -> {
                    assertThat(e.code()).isEqualTo("invalid_rule");
                    assertThat(e.violations().getFirst()).contains("customer.shoeSize");
                });
        assertThatThrownBy(() -> lifecycle.createRule(tenant, null, "LOAN", "BAD_TYPE", rule("customer.age == \"x\""), "alice"))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> lifecycle.createRule(tenant, null, "LOAN", "BAD_BUNDLE",
                new RuleContent("n", null, "customer.age > 1", UUID.randomUUID(), null, Action.ALLOW, Action.BLOCK), "alice"))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.violations().getFirst()).contains("bundle"));
        assertThatThrownBy(() -> lifecycle.createRule(tenant, null, "NO_SUCH_MODULE", "X", rule("customer.age > 1"), "alice"))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.code()).isEqualTo("unknown_module"));
        assertThat(lifecycle.listRules(tenant, null, null)).isEmpty();
    }

    @Test
    void theSubmitterCannotReviewTheirOwnRevisionButAnotherPersonCan() {
        UUID id = newRule("FOUR_EYES", "customer.age >= 18");
        lifecycle.submit(Kind.RULE, tenant, id, "alice");

        assertThatThrownBy(() -> lifecycle.approve(Kind.RULE, tenant, id, "alice", "me"))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("four_eyes"));
        assertThatThrownBy(() -> lifecycle.publish(Kind.RULE, tenant, id, "alice"))
                .as("not approved yet").isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("invalid_state"));

        Revision approved = lifecycle.approve(Kind.RULE, tenant, id, "bob", "looks right");
        assertThat(approved.state()).isEqualTo("APPROVED");
        assertThat(approved.reviewedBy()).isEqualTo("bob");
        assertThat(db.queryLong("SELECT count(*) FROM dai_re_revision WHERE reviewed_by = submitted_by")).isZero();
    }

    @Test
    void publishingMakesTheRuleLiveAndRecordsItsParameters() {
        UUID id = publishRule("LIVE_RULE", "customer.age >= 18 && loan.amount < 1000.0");

        assertThat(lifecycle.rule(tenant, id).summary().status()).isEqualTo("ACTIVE");
        assertThat(lifecycle.rule(tenant, id).published().state()).isEqualTo("PUBLISHED");
        assertThat(db.queryLong("SELECT count(*) FROM dai_re_rule_parameter p JOIN dai_re_sys_object_attribute a ON a.id = p.attribute_id"
                + " WHERE p.rule_id = '" + id + "' AND a.code IN ('age', 'amount')")).isEqualTo(2);
    }

    @Test
    void aRejectionNeedsAReasonAndTheAuthorCanReviseAndResubmit() {
        UUID id = newRule("REJECTED_ONCE", "customer.age >= 21");
        lifecycle.submit(Kind.RULE, tenant, id, "alice");

        assertThatThrownBy(() -> lifecycle.reject(Kind.RULE, tenant, id, "bob", " ")).isInstanceOf(Invalid.class);
        assertThat(lifecycle.reject(Kind.RULE, tenant, id, "bob", "threshold should be 18").state()).isEqualTo("REJECTED");

        Revision edited = lifecycle.editRule(tenant, id, rule("customer.age >= 18"), "fixed threshold", "alice");
        assertThat(edited.state()).isEqualTo("DRAFT");
        assertThat(edited.revisionNo()).as("same revision, edited in place").isEqualTo(1);
        assertThat(edited.reviewedBy()).isNull();
        assertThat(lifecycle.submit(Kind.RULE, tenant, id, "alice").state()).isEqualTo("SUBMITTED");
    }

    @Test
    void aSubmittedRevisionCannotBeEditedUntilItIsWithdrawn() {
        UUID id = newRule("LOCKED", "customer.age >= 18");
        lifecycle.submit(Kind.RULE, tenant, id, "alice");

        assertThatThrownBy(() -> lifecycle.editRule(tenant, id, rule("customer.age >= 19"), null, "alice"))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("revision_locked"));
        lifecycle.withdraw(Kind.RULE, tenant, id, "alice");
        assertThat(lifecycle.editRule(tenant, id, rule("customer.age >= 19"), null, "alice").state()).isEqualTo("DRAFT");
    }

    @Test
    void whenReviewIsNotRequiredADraftCanBePublishedDirectly() {
        UUID id = noReview.createRule(tenant, null, "LOAN", "DIRECT", rule("customer.age >= 18"), "alice").subjectId();

        assertThat(noReview.publish(Kind.RULE, tenant, id, "alice").state()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> lifecycle.publish(Kind.RULE, tenant, newRule("NEEDS_REVIEW", "customer.age >= 18"), "alice"))
                .as("with review required a draft cannot be published")
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("invalid_state"));
    }

    @Test
    void editingALiveRuleLeavesItInServiceUntilTheNewRevisionIsPublished() {
        UUID ruleId = publishRule("EVOLVING", "customer.age >= 18");
        UUID groupId = createAndPublishGroup("EVOLVING_GROUP", EvaluationPolicy.EVALUATE_ALL, ruleId);
        assertThat(evaluate("EVOLVING_GROUP", 19).response().decision()).isEqualTo(Action.ALLOW);

        Revision second = lifecycle.editRule(tenant, ruleId, rule("customer.age >= 21"), "raise the age", "alice");
        assertThat(second.revisionNo()).isEqualTo(2);
        assertThat(liveRuleExpressions()).as("draft is not live").containsExactly("customer.age >= 18");
        lifecycle.submit(Kind.RULE, tenant, ruleId, "alice");
        lifecycle.approve(Kind.RULE, tenant, ruleId, "bob", null);
        lifecycle.publish(Kind.RULE, tenant, ruleId, "carol");

        assertThat(liveRuleExpressions()).containsExactly("customer.age >= 21");
        assertThat(lifecycle.revisions(Kind.RULE, tenant, ruleId)).extracting(Revision::revisionNo, Revision::state)
                .containsExactly(org.assertj.core.api.Assertions.tuple(2, "PUBLISHED"),
                        org.assertj.core.api.Assertions.tuple(1, "SUPERSEDED"));
        assertThat(lifecycle.group(tenant, groupId).summary().status()).isEqualTo("ACTIVE");
    }

    @Test
    void rollbackRestoresAnOlderRevisionThroughTheSameLifecycle() {
        UUID ruleId = publishRule("ROLLBACK_ME", "customer.age >= 18");
        lifecycle.editRule(tenant, ruleId, rule("customer.age >= 99"), "bad change", "alice");
        assertThatThrownBy(() -> lifecycle.rollback(Kind.RULE, tenant, ruleId, 1, null, "alice"))
                .as("a revision is still open").isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("revision_open"));
        lifecycle.submit(Kind.RULE, tenant, ruleId, "alice");
        lifecycle.approve(Kind.RULE, tenant, ruleId, "bob", null);
        lifecycle.publish(Kind.RULE, tenant, ruleId, "carol");

        Revision restore = lifecycle.rollback(Kind.RULE, tenant, ruleId, 1, "bad change", "dave");
        assertThat(restore.revisionNo()).isEqualTo(3);
        assertThat(restore.rollbackOf()).isNotNull();
        assertThat(restore.state()).isEqualTo("DRAFT");
        lifecycle.submit(Kind.RULE, tenant, ruleId, "dave");
        lifecycle.approve(Kind.RULE, tenant, ruleId, "erin", null);
        lifecycle.publish(Kind.RULE, tenant, ruleId, "dave");

        assertThat(lifecycle.rule(tenant, ruleId).summary().expression()).isEqualTo("customer.age >= 18");
        assertThatThrownBy(() -> lifecycle.rollback(Kind.RULE, tenant, ruleId, 42, null, "dave")).isInstanceOf(NotFound.class);
    }

    private UUID createAndPublishGroup(String code, EvaluationPolicy policy, UUID... ruleIds) {
        List<GroupContent.Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < ruleIds.length; i++) {
            members.add(new GroupContent.Member(ruleIds[i], (i + 1) * 10, true));
        }
        GroupContent content = new GroupContent("Group " + code, null, policy, MatchOn.TRUE,
                policy == EvaluationPolicy.COMPOSITE ? OK_MSG : null, policy == EvaluationPolicy.COMPOSITE ? FAIL_MSG : null,
                Action.ALLOW, Action.BLOCK, Action.BLOCK, members);
        UUID id = lifecycle.createGroup(tenant, null, "LOAN", code, content, "alice").subjectId();
        lifecycle.submit(Kind.GROUP, tenant, id, "alice");
        lifecycle.approve(Kind.GROUP, tenant, id, "bob", null);
        lifecycle.publish(Kind.GROUP, tenant, id, "carol");
        return id;
    }

    @Test
    void aPublishedGroupOfPublishedRulesIsWhatTheEngineEvaluates() {
        UUID adult = publishRule("E2E_ADULT", "customer.age >= 18");
        createAndPublishGroup("E2E_GROUP", EvaluationPolicy.COMPOSITE, adult);

        var ok = evaluate("E2E_GROUP", 30).response();
        assertThat(ok.decision()).isEqualTo(Action.ALLOW);
        assertThat(ok.messages().getFirst().text()).isEqualTo("Customer meets the minimum age.");
        var blocked = evaluate("E2E_GROUP", 10).response();
        assertThat(blocked.decision()).isEqualTo(Action.BLOCK);
        assertThat(blocked.messages()).extracting(m -> m.text()).contains("Customer must be at least 18 years old.");
    }

    @Test
    void aGroupCannotGoLiveWithUnpublishedOrForeignRulesOrBadPolicySettings() {
        UUID draftRule = newRule("STILL_DRAFT", "customer.age >= 18");
        UUID otherTenantRule = lifecycle.createRule(UUID.randomUUID(), null, "LOAN", "THEIRS", rule("customer.age >= 18"), "x")
                .subjectId();
        GroupContent base = new GroupContent("G", null, EvaluationPolicy.EVALUATE_ALL, MatchOn.TRUE, null, null,
                Action.ALLOW, Action.BLOCK, Action.BLOCK, List.of(new GroupContent.Member(draftRule, 10, true)));

        UUID id = lifecycle.createGroup(tenant, null, "LOAN", "NOT_READY", base, "alice").subjectId();   // drafts may reference drafts
        assertThatThrownBy(() -> lifecycle.submit(Kind.GROUP, tenant, id, "alice"))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.violations().getFirst()).contains("publish it first"));

        assertThatThrownBy(() -> lifecycle.createGroup(tenant, null, "LOAN", "FOREIGN", new GroupContent("G", null,
                EvaluationPolicy.EVALUATE_ALL, MatchOn.TRUE, null, null, Action.ALLOW, Action.BLOCK, Action.BLOCK,
                List.of(new GroupContent.Member(otherTenantRule, 10, true))), "alice"))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.violations().getFirst()).contains("does not exist"));
        assertThatThrownBy(() -> lifecycle.createGroup(tenant, null, "LOAN", "BAD_MSG", new GroupContent("G", null,
                EvaluationPolicy.FIRST_MATCH, MatchOn.TRUE, OK_MSG, null, Action.ALLOW, Action.BLOCK, Action.BLOCK, List.of()),
                "alice")).isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.violations().getFirst()).contains("COMPOSITE"));
        assertThatThrownBy(() -> lifecycle.createGroup(tenant, null, "LOAN", "DUP_SEQ", new GroupContent("G", null,
                EvaluationPolicy.EVALUATE_ALL, MatchOn.TRUE, null, null, Action.ALLOW, Action.BLOCK, Action.BLOCK,
                List.of(new GroupContent.Member(draftRule, 10, true), new GroupContent.Member(UUID.randomUUID(), 10, true))),
                "alice")).isInstanceOf(Invalid.class);
    }

    @Test
    void aRuleInAnActiveGroupCannotBeRetiredUntilTheGroupIs() {
        UUID ruleId = publishRule("IN_USE", "customer.age >= 18");
        UUID groupId = createAndPublishGroup("USES_RULE", EvaluationPolicy.EVALUATE_ALL, ruleId);

        assertThatThrownBy(() -> lifecycle.retire(Kind.RULE, tenant, ruleId)).isInstanceOfSatisfying(Conflict.class, e -> {
            assertThat(e.code()).isEqualTo("rule_in_use");
            assertThat(e.details()).containsExactly("USES_RULE");
        });
        lifecycle.retire(Kind.GROUP, tenant, groupId);
        lifecycle.retire(Kind.RULE, tenant, ruleId);
        assertThat(lifecycle.rule(tenant, ruleId).summary().status()).isEqualTo("RETIRED");
        assertThat(store.loadTenant(tenant).value().groups()).as("retired things leave the engine")
                .noneMatch(g -> g.code().equals("USES_RULE"));
    }

    @Test
    void anotherTenantCannotSeeOrChangeARule() {
        UUID id = newRule("PRIVATE", "customer.age >= 18");
        UUID intruder = UUID.randomUUID();

        assertThatThrownBy(() -> lifecycle.rule(intruder, id)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> lifecycle.editRule(intruder, id, rule("customer.age >= 1"), null, "mallory")).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> lifecycle.submit(Kind.RULE, intruder, id, "mallory")).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> lifecycle.revisions(Kind.RULE, intruder, id)).isInstanceOf(NotFound.class);
        assertThat(lifecycle.listRules(intruder, null, null)).isEmpty();
    }

    @Test
    void aDuplicateCodeIsAConflictNotAServerError() {
        newRule("TWICE", "customer.age >= 18");
        assertThatThrownBy(() -> newRule("TWICE", "customer.age >= 18"))
                .isInstanceOfSatisfying(AdminException.class, e -> assertThat(e.code()).isEqualTo("duplicate_code"));
    }
}
