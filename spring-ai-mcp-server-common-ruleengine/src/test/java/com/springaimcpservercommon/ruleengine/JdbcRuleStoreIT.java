package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.store.JdbcEvaluationRecorder;
import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.store.Scope;
import com.springaimcpservercommon.ruleengine.support.RuleEngineTestDatabase;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRuleStoreIT {

    private static RuleEngineTestDatabase db;
    private static JdbcRuleStore store;

    @BeforeAll
    static void setUp() {
        db = RuleEngineTestDatabase.create(true);
        store = new JdbcRuleStore(db.dataSource(), db.schema());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Test
    void theParameterLibraryIsObjectDotAttribute() {
        List<Parameter> parameters = store.loadParameters().value();

        assertThat(parameters).extracting(Parameter::celName).contains("customer.age", "customer.creditScore",
                "loan.amount", "order.total");
        assertThat(new ParameterLibrary(parameters).find("customer.age").orElseThrow().dataType().name())
                .isEqualTo("INT");
    }

    @Test
    void messagesAreLoadedPerBundleAndLanguage() {
        var messages = store.loadMessages().value();

        var bundle = messages.get(SampleTenant.id("cccccccc", 2));
        assertThat(bundle).containsOnlyKeys("en", "hi", "th");
        assertThat(bundle.get("hi")).isEqualTo("ग्राहक की आयु कम से कम 18 वर्ष होनी चाहिए।");
    }

    @Test
    void aTenantLoadsOnlyItsActiveRulesGroupsAndTriggers() {
        var data = store.loadTenant(SampleTenant.TENANT).value();

        assertThat(data.groups()).extracting(g -> g.code()).containsExactlyInAnyOrder("LOAN_ELIGIBILITY",
                "LOAN_FIRST_FAILURE", "LOAN_ALL_FAILURES", "LOAN_FULL_REPORT");
        var composite = data.groups().stream().filter(g -> g.code().equals("LOAN_ELIGIBILITY")).findFirst().orElseThrow();
        assertThat(composite.rules()).extracting(r -> r.rule().code()).containsExactly("ADULT", "KYC_VERIFIED",
                "CREDIT_SCORE_MIN", "AMOUNT_WITHIN_LIMIT", "HIGH_VALUE_REVIEW");
        assertThat(data.triggers()).hasSize(3);
        assertThat(data.channels()).hasSize(3);
        assertThat(data.emailTemplates().values()).extracting(t -> t.templateRef()).containsExactly("TPL-1001");

        assertThat(store.loadTenant(java.util.UUID.randomUUID()).value().groups()).isEmpty();
    }

    @Test
    void everyWriteBumpsItsChangeMarkerSoOtherNodesReload() {
        long before = store.changeMarker(Scope.RULES);
        db.execute("UPDATE dai_re_rule SET description = 'x' WHERE code = 'ADULT'");
        assertThat(store.changeMarker(Scope.RULES)).isGreaterThan(before);

        long parametersBefore = store.changeMarker(Scope.PARAMETERS);
        db.execute("UPDATE dai_re_sys_object_attribute SET description = 'x' WHERE code = 'age'");
        assertThat(store.changeMarker(Scope.PARAMETERS)).isGreaterThan(parametersBefore);

        long bundlesBefore = store.changeMarker(Scope.BUNDLES);
        db.execute("UPDATE dai_re_sys_bundle_message SET message_text = message_text WHERE language = 'th'");
        assertThat(store.changeMarker(Scope.BUNDLES)).isGreaterThan(bundlesBefore);
    }

    @Test
    void aRuleEditedInTheDatabaseTakesEffectAfterTheNextPoll() {
        TestClock clock = new TestClock();
        RuleCatalogCache cache = new RuleCatalogCache(store, clock, Duration.ofSeconds(10), 10, "en");
        RuleEngine engine = new RuleEngine(cache, null, EvaluationRecorder.NONE);
        Map<String, Object> facts = Map.of("customer", Map.of("age", 20, "kycStatus", "VERIFIED", "creditScore", 700),
                "loan", Map.of("amount", 1000.0));
        var request = new EvaluationRequest(SampleTenant.TENANT, null, "LOAN", "LOAN_FIRST_FAILURE", facts, List.of());
        assertThat(engine.evaluate(request).response().matched()).as("a 20-year-old passes").isFalse();

        db.execute("UPDATE dai_re_rule SET cel_expression = 'customer.age >= 21' WHERE code = 'ADULT'");
        clock.advance(Duration.ofSeconds(11));

        var after = engine.evaluate(request).response();
        assertThat(after.matched()).isTrue();
        assertThat(after.messages()).singleElement().satisfies(m -> assertThat(m.ruleCode()).isEqualTo("ADULT"));
    }

    @Test
    void theEvaluationLogStoresDecisionsAndNeverInputValues() {
        var recorder = new JdbcEvaluationRecorder(db.dataSource(), db.schema());
        RuleCatalogCache cache = new RuleCatalogCache(store, Clock.systemUTC(), Duration.ofSeconds(10), 10, "en");
        RuleEngine engine = new RuleEngine(cache, null, recorder);
        long before = db.queryLong("SELECT count(*) FROM dai_re_evaluation");

        engine.evaluate(new EvaluationRequest(SampleTenant.TENANT, null, "LOAN", "LOAN_FULL_REPORT",
                Map.of("customer", Map.of("age", 16, "kycStatus", "SECRET-VALUE-123", "creditScore", 700, "email", "p@x.y"),
                        "loan", Map.of("amount", 1000.0)), List.of("hi")));

        assertThat(db.queryLong("SELECT count(*) FROM dai_re_evaluation")).isEqualTo(before + 1);
        assertThat(db.queryLong("SELECT count(*) FROM dai_re_evaluation_result r JOIN dai_re_evaluation e ON e.id = r.evaluation_id"
                + " WHERE e.language = 'hi' AND e.decision = 'BLOCK' AND r.outcome = 'FALSE' AND r.error_code IS NULL"))
                .isEqualTo(2); // ADULT and KYC_VERIFIED failed
        assertThat(db.queryLong("SELECT count(*) FROM dai_re_evaluation WHERE row_to_json(dai_re_evaluation)::text LIKE '%SECRET-VALUE-123%'"))
                .isZero();
    }

    @Test
    void theSchemaRefusesWhatTheEngineCannotRun() {
        assertThatThrownBy(() -> db.execute("INSERT INTO dai_re_rule_group (tenant_id, module_id, code, name, evaluation_policy)"
                + " SELECT tenant_id, module_id, 'BAD', 'bad', 'SOMETIMES' FROM dai_re_rule_group LIMIT 1"))
                .hasMessageContaining("ck_re_rule_group_policy");
        assertThatThrownBy(() -> db.execute("INSERT INTO dai_re_api_endpoint (tenant_id, name, url, environment)"
                + " VALUES (gen_random_uuid(), 'ext', 'https://partner.example.org/h', 'EXTERNAL')"))
                .hasMessageContaining("ck_re_api_endpoint_confirmation");
        assertThatThrownBy(() -> db.execute("INSERT INTO dai_re_sys_object (code, name) VALUES ('Customer', 'x')"))
                .hasMessageContaining("ck_re_sys_object_code");
        assertThatThrownBy(() -> db.execute("INSERT INTO dai_re_sys_object_attribute (object_id, code, name, data_type)"
                + " SELECT id, 'age', 'dup', 'INT' FROM dai_re_sys_object WHERE code = 'customer'"))
                .hasMessageContaining("uq_re_sys_object_attribute");
    }

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }
}
