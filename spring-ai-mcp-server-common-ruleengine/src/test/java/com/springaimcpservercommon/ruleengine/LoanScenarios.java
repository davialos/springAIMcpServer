package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult.Status;
import com.springaimcpservercommon.ruleengine.channel.EmailMessage;
import com.springaimcpservercommon.ruleengine.channel.PushMessage;
import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.response.MessageSource;
import com.springaimcpservercommon.ruleengine.response.ResponseDetail;
import com.springaimcpservercommon.ruleengine.response.ResponseMessage;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour of the four evaluation policies, multilingual messages, error handling, trigger points and
 * communications, on the sample loan tenant. Subclasses supply the store: in memory (unit test) or PostgreSQL loaded
 * from {@code scripts/rule-engine/sample-data.sql} (integration test), so both prove the same behaviour.
 */
public abstract class LoanScenarios {

    protected abstract RuleStore store();

    // ---- helpers -------------------------------------------------------------------------------------------------

    private final List<EmailMessage> emails = new ArrayList<>();
    private final List<PushMessage> pushes = new ArrayList<>();
    private final List<String> apiCalls = new ArrayList<>();

    private RuleEngine engine(EnvironmentTier tier) {
        RuleCatalogCache cache = new RuleCatalogCache(store(), Clock.systemUTC(), Duration.ofSeconds(30), 10, "en");
        ChannelDispatcher dispatcher = new ChannelDispatcher(emails::add, pushes::add,
                (endpoint, body) -> apiCalls.add(endpoint.name() + " " + body), new ApiEnvironmentPolicy(tier));
        return new RuleEngine(cache, dispatcher, EvaluationRecorder.NONE);
    }

    private RuleEngine engine() {
        return engine(EnvironmentTier.DEV);
    }

    private static Map<String, Object> good() {
        Map<String, Object> facts = new HashMap<>();
        facts.put("customer", new HashMap<>(Map.of("age", 34, "kycStatus", "VERIFIED", "creditScore", 720,
                "email", "a@example.com")));
        facts.put("loan", new HashMap<>(Map.of("amount", 250000.0, "tenureMonths", 36)));
        return facts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> with(String object, String attribute, Object value) {
        Map<String, Object> facts = good();
        ((Map<String, Object>) facts.get(object)).put(attribute, value);
        return facts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> without(String object, String attribute) {
        Map<String, Object> facts = good();
        ((Map<String, Object>) facts.get(object)).remove(attribute);
        return facts;
    }

    private static Map<String, Object> underage() {
        Map<String, Object> facts = with("customer", "age", 16);
        return merge(facts, "customer", "kycStatus", "PENDING");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> facts, String object, String attribute, Object v) {
        ((Map<String, Object>) facts.get(object)).put(attribute, v);
        return facts;
    }

    private static EvaluationRequest request(String group, Map<String, Object> facts, String... languages) {
        return new EvaluationRequest(SampleTenant.TENANT, null, "LOAN", group, facts, List.of(languages));
    }

    private EvaluationResponse run(String group, Map<String, Object> facts, String... languages) {
        return engine().evaluate(request(group, facts, languages), ResponseDetail.WITH_RAW, false).response();
    }

    private static List<String> codes(EvaluationResponse r) {
        return r.messages().stream().map(m -> m.ruleCode() == null ? "<group>" : m.ruleCode()).toList();
    }

    // ---- COMPOSITE -----------------------------------------------------------------------------------------------

    @Test
    void compositeAllTrueReportsOnlyTheGroupTrueMessage() {
        EvaluationResponse r = run("LOAN_ELIGIBILITY", good());

        assertThat(r.decision()).isEqualTo(Action.ALLOW);
        assertThat(r.matched()).isTrue();
        assertThat(r.messages()).singleElement().satisfies(m -> {
            assertThat(m.source()).isEqualTo(MessageSource.GROUP);
            assertThat(m.text()).isEqualTo("All eligibility checks passed.");
        });
        assertThat(r.primaryMessage()).isEqualTo(r.messages().getFirst());
        assertThat(r.results()).hasSize(5).allSatisfy(x -> assertThat(x.outcome()).isNotEqualTo(Outcome.ERROR));
    }

    @Test
    void compositeAnyFalseReportsGroupFalseMessageThenTheFailingRules() {
        EvaluationResponse r = run("LOAN_ELIGIBILITY", underage());

        assertThat(r.decision()).isEqualTo(Action.BLOCK);
        assertThat(r.matched()).isFalse();
        assertThat(codes(r)).containsExactly("<group>", "ADULT", "KYC_VERIFIED");
        assertThat(r.messages().getFirst().text()).isEqualTo("Loan eligibility checks failed.");
        assertThat(r.primaryMessage()).isEqualTo(r.messages().getFirst());
    }

    // ---- multilingual messages -------------------------------------------------------------------------------------

    @Test
    void messagesComeInTheCallersLanguageWithFallback() {
        assertThat(run("LOAN_ELIGIBILITY", underage(), "hi").messages().getFirst().text())
                .isEqualTo("ऋण पात्रता जाँच विफल रही।");
        assertThat(run("LOAN_ELIGIBILITY", underage(), "th-TH").messages().get(1).text())
                .isEqualTo("ลูกค้าต้องมีอายุอย่างน้อย 18 ปี");
        // fr is not translated: first requested language with a text wins, then the default
        ResponseMessage fallback = run("LOAN_ELIGIBILITY", underage(), "fr", "hi").messages().getFirst();
        assertThat(fallback.language()).isEqualTo("hi");
        assertThat(run("LOAN_ELIGIBILITY", underage(), "fr").messages().getFirst().language()).isEqualTo("en");
        assertThat(run("LOAN_ELIGIBILITY", underage()).messages().getFirst().language()).isEqualTo("en");
    }

    // ---- FIRST_MATCH -----------------------------------------------------------------------------------------------

    @Test
    void firstMatchOnFalseStopsAtTheFirstFailingRule() {
        EvaluationResponse r = run("LOAN_FIRST_FAILURE", underage());

        assertThat(codes(r)).containsExactly("ADULT");
        assertThat(r.results()).as("evaluation stopped after the first match").hasSize(1);
        assertThat(r.decision()).isEqualTo(Action.BLOCK);
        assertThat(r.matched()).isTrue();
    }

    @Test
    void firstMatchWithNothingMatchingAllowsAndReportsNoMessage() {
        EvaluationResponse r = run("LOAN_FIRST_FAILURE", good());

        assertThat(r.matched()).isFalse();
        assertThat(r.messages()).isEmpty();
        assertThat(r.primaryMessage()).isNull();
        assertThat(r.decision()).isEqualTo(Action.ALLOW);
        assertThat(r.results()).hasSize(5);
    }

    // ---- ALL_MATCH -------------------------------------------------------------------------------------------------

    @Test
    void allMatchOnFalseReportsEveryFailingRule() {
        EvaluationResponse r = run("LOAN_ALL_FAILURES", underage());

        assertThat(codes(r)).containsExactly("ADULT", "KYC_VERIFIED");
        assertThat(r.decision()).isEqualTo(Action.BLOCK);
    }

    // ---- EVALUATE_ALL ---------------------------------------------------------------------------------------------

    @Test
    void evaluateAllReportsTrueAndFalseMessagesAndTheStrictestAction() {
        EvaluationResponse ok = run("LOAN_FULL_REPORT", good());
        assertThat(codes(ok)).containsExactly("ADULT", "KYC_VERIFIED", "CREDIT_SCORE_MIN", "AMOUNT_WITHIN_LIMIT");
        assertThat(ok.decision()).isEqualTo(Action.ALLOW);

        EvaluationResponse review = run("LOAN_FULL_REPORT", with("loan", "amount", 600000.0));
        assertThat(codes(review)).contains("HIGH_VALUE_REVIEW");
        assertThat(review.decision()).isEqualTo(Action.WARN);
        assertThat(review.primaryMessage().text()).isEqualTo("High-value loan: manual review is recommended.");
    }

    // ---- errors fail closed ---------------------------------------------------------------------------------------

    @Test
    void aMissingParameterIsAnErrorAndBlocksByDefault() {
        EvaluationResponse r = run("LOAN_FULL_REPORT", without("customer", "creditScore"));

        assertThat(r.decision()).isEqualTo(Action.BLOCK);
        List<RuleResult> errors = r.results().stream().filter(x -> x.outcome() == Outcome.ERROR).toList();
        assertThat(errors).extracting(RuleResult::ruleCode).containsExactly("CREDIT_SCORE_MIN", "AMOUNT_WITHIN_LIMIT");
        assertThat(errors).allSatisfy(x -> {
            assertThat(x.errorCode()).isEqualTo("MISSING_PARAMETER");
            assertThat(x.errorDetail()).contains("customer.creditScore");
        });
    }

    @Test
    void aWronglyTypedValueIsAnInvalidParameterAndNeverEchoedBack() {
        EvaluationResponse r = run("LOAN_ALL_FAILURES", with("customer", "age", "not-a-number"));

        assertThat(r.results()).filteredOn(x -> x.outcome() == Outcome.ERROR).singleElement().satisfies(x -> {
            assertThat(x.ruleCode()).isEqualTo("ADULT");
            assertThat(x.errorCode()).isEqualTo("INVALID_PARAMETER");
            assertThat(x.errorDetail()).doesNotContain("not-a-number");
        });
        assertThat(r.decision()).isEqualTo(Action.BLOCK);
    }

    @Test
    void flatDottedFactsWorkLikeNestedOnes() {
        Map<String, Object> flat = new HashMap<>();
        flat.put("customer.age", 34L);
        flat.put("customer.kycStatus", "VERIFIED");
        flat.put("customer.creditScore", 720);
        flat.put("loan.amount", 250000);   // an int where a double is declared is widened
        assertThat(run("LOAN_ELIGIBILITY", flat).decision()).isEqualTo(Action.ALLOW);
    }

    @Test
    void aGroupThatDoesNotExistIsRejected() {
        assertThatThrownBy(() -> engine().evaluate(request("NOPE", good())))
                .isInstanceOf(UnknownRuleGroupException.class);
    }

    // ---- trigger points -------------------------------------------------------------------------------------------

    private TriggerRequest trigger(TriggerType type, String action, String field, Map<String, Object> facts) {
        return new TriggerRequest(SampleTenant.TENANT, null, "loan-portal", type, "LOAN_APPLICATION", action, field,
                facts, List.of("en"));
    }

    @Test
    void aFormActionRunsTheBoundGroup() {
        TriggerResult blocked = engine().evaluate(trigger(TriggerType.FORM_ACTION, "SUBMIT", null, underage()));
        assertThat(blocked.decision()).isEqualTo(Action.BLOCK);
        assertThat(blocked.groups()).singleElement()
                .satisfies(g -> assertThat(g.response().groupCode()).isEqualTo("LOAN_ELIGIBILITY"));

        assertThat(engine().evaluate(trigger(TriggerType.FORM_ACTION, "SUBMIT", null, good())).decision())
                .isEqualTo(Action.ALLOW);
    }

    @Test
    void aFormFieldTriggerIsSeparateFromTheFormAction() {
        TriggerResult field = engine().evaluate(trigger(TriggerType.FORM_FIELD, "ON_CHANGE", "amount", underage()));
        assertThat(field.groups()).singleElement()
                .satisfies(g -> assertThat(g.response().groupCode()).isEqualTo("LOAN_FIRST_FAILURE"));

        TriggerResult other = engine().evaluate(trigger(TriggerType.FORM_FIELD, "ON_CHANGE", "tenure", underage()));
        assertThat(other.groups()).isEmpty();
        assertThat(other.decision()).as("nothing bound = nothing to enforce").isEqualTo(Action.ALLOW);
    }

    // ---- communications -------------------------------------------------------------------------------------------

    @Test
    void aFailedCompositePlansEmailPushAndApiAndDispatchesThemInDev() {
        EvaluationResult r = engine(EnvironmentTier.DEV).evaluate(
                request("LOAN_ELIGIBILITY", underage(), "th"), ResponseDetail.MESSAGES, true);

        assertThat(r.planned()).extracting(p -> p.binding().type())
                .containsExactly(ChannelType.EMAIL, ChannelType.PUSH, ChannelType.API);
        assertThat(r.dispatched()).extracting(DispatchResult::status).containsOnly(Status.SENT);

        assertThat(emails).singleElement().satisfies(m -> {
            assertThat(m.templateRef()).isEqualTo("TPL-1001");
            assertThat(m.recipient()).isEqualTo("a@example.com");
            assertThat(m.decision()).isEqualTo(Action.BLOCK);
        });
        assertThat(pushes).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("คำขอสินเชื่อถูกระงับ");
            assertThat(m.language()).isEqualTo("th");
        });
        assertThat(apiCalls).singleElement().satisfies(call -> {
            assertThat(call).startsWith("loan-decision-webhook-dev").contains("\"decision\":\"BLOCK\"");
            assertThat(call).as("input values are never sent").doesNotContain("a@example.com");
        });
    }

    @Test
    void aPassingCompositeOnlyFiresTheAnyBinding() {
        EvaluationResult r = engine().evaluate(request("LOAN_ELIGIBILITY", good()), ResponseDetail.MESSAGES, true);

        assertThat(r.planned()).extracting(p -> p.binding().type()).containsExactly(ChannelType.API);
        assertThat(emails).isEmpty();
        assertThat(pushes).isEmpty();
    }

    @Test
    void aDevApiIsRefusedInProductionAndOtherChannelsStillGo() {
        EvaluationResult r = engine(EnvironmentTier.PROD).evaluate(
                request("LOAN_ELIGIBILITY", underage()), ResponseDetail.MESSAGES, true);

        assertThat(r.dispatched()).extracting(DispatchResult::type, DispatchResult::status)
                .containsExactly(org.assertj.core.api.Assertions.tuple(ChannelType.EMAIL, Status.SENT),
                        org.assertj.core.api.Assertions.tuple(ChannelType.PUSH, Status.SENT),
                        org.assertj.core.api.Assertions.tuple(ChannelType.API, Status.REFUSED));
        assertThat(r.dispatched().getLast().reason()).isEqualTo("api.environment.mismatch");
        assertThat(apiCalls).isEmpty();
    }

    @Test
    void withoutDispatchingNothingIsSent() {
        engine().evaluate(request("LOAN_ELIGIBILITY", underage()));
        assertThat(emails).isEmpty();
        assertThat(apiCalls).isEmpty();
    }
}
