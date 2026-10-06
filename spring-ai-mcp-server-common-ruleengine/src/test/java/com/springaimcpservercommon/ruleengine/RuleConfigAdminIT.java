package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Conflict;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Invalid;
import com.springaimcpservercommon.ruleengine.admin.AdminException.NotFound;
import com.springaimcpservercommon.ruleengine.admin.GroupContent;
import com.springaimcpservercommon.ruleengine.admin.Revision.Kind;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin.ChannelInput;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin.TriggerInput;
import com.springaimcpservercommon.ruleengine.admin.RuleContent;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentClassifier;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import com.springaimcpservercommon.ruleengine.model.ChannelTrigger;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.store.JdbcEvaluationRecorder;
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

/** Library, bundles, templates, API endpoints, channels, triggers: validation, tenant isolation, impact checks. */
class RuleConfigAdminIT {

    private static RuleEngineTestDatabase db;
    private static JdbcRuleStore store;
    private static RuleConfigAdmin admin;
    private static RuleLifecycle lifecycle;

    private final UUID tenant = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        db = RuleEngineTestDatabase.create(true);
        store = new JdbcRuleStore(db.dataSource(), db.schema());
        ParameterLibrary library = new ParameterLibrary(store.loadParameters().value());
        admin = new RuleConfigAdmin(db.dataSource(), db.schema(), () -> new ParameterLibrary(store.loadParameters().value()),
                new ApiEnvironmentPolicy(EnvironmentTier.PROD),
                new ApiEnvironmentClassifier(Map.of(ApiEnvironment.DEV, List.of("localhost", "*.dev.acme.com"),
                        ApiEnvironment.PROD, List.of("api.acme.com"))));
        lifecycle = new RuleLifecycle(db.dataSource(), db.schema(), () -> library, true);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private UUID publishedRule(String code, String expression) {
        UUID id = lifecycle.createRule(tenant, null, "LOAN", code, new RuleContent("r", null, expression, null, null,
                Action.ALLOW, Action.BLOCK), "alice").subjectId();
        lifecycle.submit(Kind.RULE, tenant, id, "alice");
        lifecycle.approve(Kind.RULE, tenant, id, "bob", null);
        lifecycle.publish(Kind.RULE, tenant, id, "carol");
        return id;
    }

    private UUID group(String code, UUID rule) {
        return lifecycle.createGroup(tenant, null, "LOAN", code, new GroupContent("g", null, EvaluationPolicy.EVALUATE_ALL,
                MatchOn.TRUE, null, null, Action.ALLOW, Action.BLOCK, Action.BLOCK,
                List.of(new GroupContent.Member(rule, 10, true))), "alice").subjectId();
    }

    // ---- library -------------------------------------------------------------------------------------------------

    @Test
    void theLibraryListsObjectsWithTheirAttributesAndCelNames() {
        var customer = admin.objects().stream().filter(o -> o.code().equals("customer")).findFirst().orElseThrow();

        assertThat(customer.attributes()).extracting(a -> a.celName()).contains("customer.age", "customer.kycStatus");
        assertThat(admin.modules()).extracting(m -> m.code()).contains("LOAN", "ORDERS");
    }

    @Test
    void newObjectsAndAttributesBecomeCelVariablesAndBadInputIsRefused() {
        UUID object = admin.createObject("vehicle", "Vehicle", null, "ORDERS");
        UUID attribute = admin.createAttribute(object, "year", "Model year", null, DataType.INT, true, "2020");

        assertThat(store.loadParameters().value()).extracting(p -> p.celName()).contains("vehicle.year");
        assertThatThrownBy(() -> admin.createObject("vehicle", "again", null, null))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("duplicate"));
        assertThatThrownBy(() -> admin.createObject("Vehicle2", "bad code", null, null)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.createObject("while", "keyword", null, null)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.createAttribute(UUID.randomUUID(), "x", "x", null, DataType.INT, false, null))
                .isInstanceOf(NotFound.class);
        assertThat(attribute).isNotNull();
    }

    @Test
    void aParameterThatARuleReadsCannotBeRetypedOrDeactivated() {
        UUID age = store.loadParameters().value().stream().filter(p -> p.celName().equals("customer.age")).findFirst()
                .orElseThrow().attributeId();
        publishedRule("READS_AGE", "customer.age >= 18");

        assertThat(admin.parameterUsage(age)).contains("READS_AGE");
        assertThatThrownBy(() -> admin.updateAttribute(age, "Age", null, DataType.STRING, true, null, true))
                .isInstanceOfSatisfying(Conflict.class, e -> {
                    assertThat(e.code()).isEqualTo("parameter_in_use");
                    assertThat(e.details()).contains("READS_AGE");
                });
        assertThatThrownBy(() -> admin.updateAttribute(age, "Age", null, DataType.INT, true, null, false))
                .isInstanceOf(Conflict.class);
        UUID customerObject = admin.objects().stream().filter(o -> o.code().equals("customer")).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> admin.updateObject(customerObject, "Customer", null, null, false)).isInstanceOf(Conflict.class);

        admin.updateAttribute(age, "Age in years (edited)", "doc", DataType.INT, true, "34", true);   // harmless edits are fine
    }

    // ---- bundles -------------------------------------------------------------------------------------------------

    @Test
    void tenantBundlesAreIsolatedAndPlatformBundlesAreReadOnlyToTenants() {
        var mine = admin.createBundle(tenant, "my.msg", "mine", Map.of("en", "Hello", "hi", "नमस्ते"));
        UUID platform = SampleTenant.id("cccccccc", 1);

        assertThat(admin.bundles(tenant)).extracting(b -> b.code()).contains("my.msg", "loan.age.ok");
        assertThat(admin.bundles(UUID.randomUUID())).extracting(b -> b.code()).doesNotContain("my.msg");
        assertThatThrownBy(() -> admin.putText(tenant, platform, "en", "hacked")).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> admin.putText(UUID.randomUUID(), mine.id(), "en", "hacked")).isInstanceOf(NotFound.class);

        admin.putText(tenant, mine.id(), "th", "สวัสดี");
        admin.putText(tenant, mine.id(), "en", "Hello!");
        assertThat(admin.bundles(tenant).stream().filter(b -> b.code().equals("my.msg")).findFirst().orElseThrow().texts())
                .containsEntry("en", "Hello!").containsEntry("th", "สวัสดี").hasSize(3);
        assertThatThrownBy(() -> admin.putText(tenant, mine.id(), "English", "x")).isInstanceOf(Invalid.class);

        admin.deleteText(tenant, mine.id(), "th");
        admin.deleteText(tenant, mine.id(), "hi");
        assertThatThrownBy(() -> admin.deleteText(tenant, mine.id(), "en"))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("last_text"));
        admin.deleteBundle(tenant, mine.id());
        assertThat(admin.bundles(tenant)).extracting(b -> b.code()).doesNotContain("my.msg");
    }

    @Test
    void aBundleInUseCannotBeDeleted() {
        var bundle = admin.createBundle(tenant, "used.msg", null, Map.of("en", "x"));
        lifecycle.createRule(tenant, null, "LOAN", "USES_BUNDLE", new RuleContent("r", null, "customer.age > 1", bundle.id(),
                null, Action.ALLOW, Action.BLOCK), "alice");

        assertThatThrownBy(() -> admin.deleteBundle(tenant, bundle.id()))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("bundle_in_use"));
    }

    // ---- templates and endpoints ---------------------------------------------------------------------------------

    @Test
    void emailTemplatesShowTheCallerSideIdAndNameAndStayWithinTheirTenant() {
        var t = admin.saveEmailTemplate(tenant, null, "TPL-9", "Welcome", null, true);

        assertThat(admin.emailTemplates(tenant)).extracting(x -> x.templateRef() + "/" + x.name()).contains("TPL-9/Welcome");
        admin.saveEmailTemplate(tenant, t.id(), "TPL-9", "Welcome (new)", null, true);
        assertThatThrownBy(() -> admin.saveEmailTemplate(UUID.randomUUID(), t.id(), "TPL-9", "x", null, true))
                .isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> admin.saveEmailTemplate(tenant, null, "TPL-9", "dup", null, true))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("duplicate"));
    }

    @Test
    void anApiOfAnotherEnvironmentIsRejectedAndAnUnknownOneNeedsAConfirmationThatIsRecorded() {
        // this node runs as PROD
        assertThatThrownBy(() -> admin.saveApiEndpoint(tenant, null, "dev-hook", "POST", "http://localhost:8080/hook", 3000,
                null, false, "alice", true))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.code()).isEqualTo("api_environment_mismatch"));

        var prod = admin.saveApiEndpoint(tenant, null, "prod-hook", "POST", "https://api.acme.com/hook", 3000, null, false,
                "alice", true);
        assertThat(prod.environment()).isEqualTo(ApiEnvironment.PROD);
        assertThat(prod.externalConfirmedBy()).isNull();

        assertThat(admin.checkApiUrl("https://partner.example.org/h").verdict()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(admin.checkApiUrl("https://partner.example.org/h").message()).contains("external API");
        assertThat(admin.checkApiUrl("https://api.acme.com/h").verdict()).isEqualTo("ALLOWED");
        assertThat(admin.checkApiUrl("http://localhost/h").verdict()).isEqualTo("REJECTED_ENVIRONMENT_MISMATCH");

        assertThatThrownBy(() -> admin.saveApiEndpoint(tenant, null, "partner", "POST", "https://partner.example.org/h", 3000,
                null, false, "alice", true))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("confirmation_required"));
        assertThat(admin.apiEndpoints(tenant)).extracting(e -> e.name()).doesNotContain("partner");

        var confirmed = admin.saveApiEndpoint(tenant, null, "partner", "POST", "https://partner.example.org/h", 3000, null, true,
                "alice", true);
        assertThat(confirmed.environment()).isEqualTo(ApiEnvironment.EXTERNAL);
        assertThat(confirmed.externalConfirmedBy()).isEqualTo("alice");
        assertThat(confirmed.externalConfirmedAt()).isNotNull();

        assertThatThrownBy(() -> admin.saveApiEndpoint(tenant, null, "bad-url", "POST", "ftp://x", 3000, null, true, "a", true))
                .isInstanceOf(Invalid.class);
    }

    // ---- channels ------------------------------------------------------------------------------------------------

    @Test
    void channelsAreValidatedAgainstTheirOwnerTemplateAndRecipientExpression() {
        UUID rule = publishedRule("CH_RULE", "customer.age >= 18");
        UUID group = group("CH_GROUP", rule);
        var template = admin.saveEmailTemplate(tenant, null, "TPL-CH", "Channel mail", null, true);

        var ok = admin.saveChannel(tenant, null, new ChannelInput(OwnerType.GROUP, group, ChannelTrigger.FALSE, ChannelType.EMAIL,
                10, true, template.id(), null, null, null, "customer.email"));
        assertThat(admin.channels(tenant, OwnerType.GROUP, group)).extracting(c -> c.id()).containsExactly(ok.id());

        assertThatThrownBy(() -> admin.saveChannel(tenant, null, new ChannelInput(OwnerType.GROUP, group, ChannelTrigger.FALSE,
                ChannelType.EMAIL, 20, true, template.id(), null, null, null, "customer.age")))
                .as("recipient must be a string").isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.saveChannel(tenant, null, new ChannelInput(OwnerType.GROUP, group, ChannelTrigger.FALSE,
                ChannelType.EMAIL, 20, true, template.id(), null, null, null, null))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.saveChannel(tenant, null, new ChannelInput(OwnerType.GROUP, group, ChannelTrigger.ANY,
                ChannelType.API, 20, true, template.id(), null, null, null, null))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.saveChannel(UUID.randomUUID(), null, new ChannelInput(OwnerType.GROUP, group,
                ChannelTrigger.FALSE, ChannelType.EMAIL, 10, true, template.id(), null, null, null, "customer.email")))
                .as("other tenant's group").isInstanceOf(Invalid.class);

        admin.deleteChannel(tenant, ok.id());
        assertThatThrownBy(() -> admin.deleteChannel(tenant, ok.id())).isInstanceOf(NotFound.class);
    }

    // ---- triggers ------------------------------------------------------------------------------------------------

    @Test
    void triggerPointsBindAFormActionOrFieldToAGroupOfTheSameModule() {
        UUID group = group("TR_GROUP", publishedRule("TR_RULE", "customer.age >= 18"));

        var submit = admin.saveTrigger(tenant, null, new TriggerInput(null, "portal", "LOAN", TriggerType.FORM_ACTION, "APPLY",
                "SUBMIT", null, group, 0, true));
        var field = admin.saveTrigger(tenant, null, new TriggerInput(null, "portal", "LOAN", TriggerType.FORM_FIELD, "APPLY",
                "ON_CHANGE", "amount", group, 0, true));
        assertThat(admin.triggers(tenant, "portal")).extracting(t -> t.id()).containsExactlyInAnyOrder(submit.id(), field.id());

        assertThatThrownBy(() -> admin.saveTrigger(tenant, null, new TriggerInput(null, "portal", "LOAN", TriggerType.FORM_FIELD,
                "APPLY", "ON_CHANGE", null, group, 0, true))).as("field needs a field code").isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.saveTrigger(tenant, null, new TriggerInput(null, "portal", "ORDERS", TriggerType.FORM_ACTION,
                "APPLY", "ADD", null, group, 0, true))).as("module mismatch").isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> admin.saveTrigger(tenant, null, new TriggerInput(null, "portal", "LOAN", TriggerType.FORM_ACTION,
                "APPLY", "SUBMIT", null, group, 0, true)))
                .isInstanceOfSatisfying(Conflict.class, e -> assertThat(e.code()).isEqualTo("duplicate"));
        assertThatThrownBy(() -> admin.saveTrigger(UUID.randomUUID(), null, new TriggerInput(null, "portal", "LOAN",
                TriggerType.FORM_ACTION, "APPLY", "APPROVE", null, group, 0, true))).isInstanceOf(Invalid.class);

        admin.deleteTrigger(tenant, field.id());
        assertThat(admin.triggers(tenant, null)).hasSize(1);
    }

    // ---- evaluation log ------------------------------------------------------------------------------------------

    @Test
    void evaluationsAreListedWithDecisionsOnly() {
        UUID rule = publishedRule("LOG_RULE", "customer.age >= 18");
        UUID group = group("LOG_GROUP", rule);
        lifecycle.submit(Kind.GROUP, tenant, group, "alice");
        lifecycle.approve(Kind.GROUP, tenant, group, "bob", null);
        lifecycle.publish(Kind.GROUP, tenant, group, "carol");
        var cache = new RuleCatalogCache(store, Clock.systemUTC(), Duration.ofSeconds(1), 5, "en");
        var engine = new RuleEngine(cache, null, new JdbcEvaluationRecorder(db.dataSource(), db.schema()));
        engine.evaluate(new EvaluationRequest(tenant, null, "LOAN", "LOG_GROUP", Map.of("customer", Map.of("age", 9)), List.of("th")));

        var rows = admin.evaluations(tenant, group, 10);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.decision()).isEqualTo("BLOCK");
            assertThat(r.policy()).isEqualTo("EVALUATE_ALL");
            assertThat(r.language()).isEqualTo("th");
        });
        assertThat(admin.evaluations(UUID.randomUUID(), group, 10)).isEmpty();
    }
}
