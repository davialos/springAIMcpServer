package com.springaimcpservercommon.ruleengine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded ACME tenant evaluated over HTTP against a real PostgreSQL: one request per evaluation policy, the
 * languages, the final action, the channels and the audit trail.
 */
class EvaluationIT extends AbstractIT {

    static List<String> texts(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.path("messages").forEach(m -> out.add(m.path("text").asString()));
        return out;
    }

    private static String loan(int age, String kyc, int score, long income, long principal, String language) {
        return """
                {"module":"LENDING","trigger":{"form":"LOAN_APPLICATION","action":"SUBMIT"},"language":"%s","detailed":true,
                 "context":{"customer":{"age":%d,"kycStatus":"%s","creditScore":%d,"income":%d,"email":"jo@acme.test"},
                            "loan":{"principal":%d}}}""".formatted(language, age, kyc, score, income, principal);
    }

    @Test
    void compositeAnswersWithTheGroupMessageWhenEveryRulePasses() throws Exception {
        JsonNode r = post("/api/v1/evaluate", loan(35, "VERIFIED", 720, 80000, 200000, "en"));
        assertThat(r.path("result").asBoolean()).isTrue();
        assertThat(r.path("action").asString()).isEqualTo("ALLOW");
        assertThat(r.path("allowed").asBoolean()).isTrue();
        JsonNode composite = r.path("groups").get(0);
        assertThat(composite.path("code").asString()).isEqualTo("LOAN_ELIGIBILITY");
        List<String> own = new ArrayList<>();
        composite.path("messages").forEach(m -> own.add(m.path("text").asString()));
        assertThat(own).as("composite: only the group's message, not each passing rule's").containsExactly("The applicant is eligible for this loan.");
        // the advisory group (EVALUATE_ALL) reports every rule, true and false
        assertThat(texts(r)).contains("Age 35 is within the allowed range.");
    }

    @Test
    void compositeListsEveryFailingRuleThenTheGroupMessageInTheRequestedLanguage() throws Exception {
        JsonNode r = post("/api/v1/evaluate", loan(17, "PENDING", 720, 80000, 200000, "hi"));
        assertThat(r.path("result").asBoolean()).isFalse();
        assertThat(r.path("action").asString()).isEqualTo("BLOCK");
        assertThat(r.path("allowed").asBoolean()).isFalse();
        assertThat(texts(r)).contains("आवेदक की आयु 18 से 70 वर्ष के बीच होनी चाहिए (दी गई आयु: 17)।",
                "केवाईसी सत्यापन पूरा नहीं हुआ है।", "आवेदक इस ऋण के लिए पात्र नहीं है।");
        JsonNode eligibility = r.path("groups").get(0);
        assertThat(eligibility.path("code").asString()).isEqualTo("LOAN_ELIGIBILITY");
        assertThat(eligibility.path("rules").size()).as("composite evaluates every rule").isEqualTo(4);
    }

    @Test
    void thaiAndTheTenantsDefaultLanguage() throws Exception {
        assertThat(texts(post("/api/v1/evaluate", loan(17, "VERIFIED", 720, 80000, 200000, "th"))))
                .contains("ผู้สมัครไม่มีสิทธิ์ได้รับสินเชื่อนี้");
        assertThat(texts(post("/api/v1/evaluate", loan(17, "VERIFIED", 720, 80000, 200000, "fr"))))
                .as("a language without text falls back to the tenant default").contains("The applicant is not eligible for this loan.");
    }

    @Test
    void firstMatchStopsAtTheFirstFailingRule() throws Exception {
        JsonNode r = post("/api/v1/evaluate", """
                {"module":"PAYMENTS","trigger":{"form":"FUNDS_TRANSFER","action":"SUBMIT"},"detailed":true,
                 "context":{"transaction":{"amount":250000,"international":false},"account":{"balance":1000}}}""");
        assertThat(r.path("action").asString()).isEqualTo("BLOCK");
        JsonNode checks = null;
        for (JsonNode g : r.path("groups")) {
            if (g.path("code").asString().equals("TXN_CHECKS")) {
                checks = g;
            }
        }
        assertThat(checks.path("rules").size()).as("FIRST_MATCH on FALSE stops at the balance rule").isEqualTo(1);
        assertThat(checks.path("messages").get(0).path("text").asString())
                .isEqualTo("Insufficient balance: 250000 requested, 1000 available.");
        assertThat(texts(r)).noneMatch(t -> t.contains("limit of 100000"));
    }

    @Test
    void allMatchReportsEveryFlagAndTheGroupMessage() throws Exception {
        JsonNode r = post("/api/v1/evaluate", """
                {"module":"PAYMENTS","trigger":{"form":"FUNDS_TRANSFER","action":"SUBMIT"},
                 "context":{"transaction":{"amount":60000,"international":true,"country":"KP"},"account":{"balance":900000}}}""");
        assertThat(r.path("action").asString()).isEqualTo("WARN");
        assertThat(r.path("allowed").asBoolean()).isTrue();
        assertThat(texts(r)).contains("Large transaction (60000); it may be reviewed.",
                "Transfers to KP need additional screening.", "The transaction was flagged for review.",
                "No limit or balance problem was found.");
    }

    @Test
    void aFieldLevelTriggerPointAndErrorsInRules() throws Exception {
        JsonNode ok = post("/api/v1/evaluate", """
                {"module":"PAYMENTS","trigger":{"form":"FUNDS_TRANSFER","action":"CHANGE","field":"amount"},
                 "context":{"transaction":{"amount":10},"account":{"balance":100}}}""");
        assertThat(ok.path("trigger").asString()).isEqualTo("FORM:FUNDS_TRANSFER/CHANGE/amount");
        assertThat(ok.path("action").asString()).isEqualTo("ALLOW");
        // no account in the context: the balance rule cannot be evaluated and counts as false (fail-safe)
        JsonNode missing = post("/api/v1/evaluate", """
                {"module":"PAYMENTS","trigger":{"form":"FUNDS_TRANSFER","action":"CHANGE","field":"amount"},"detailed":true,
                 "context":{"transaction":{"amount":10}}}""");
        assertThat(missing.path("action").asString()).isEqualTo("BLOCK");
        assertThat(missing.path("groups").get(0).path("rules").get(0).path("error").asString()).isNotBlank();
    }

    @Test
    void channelsRunAfterTheOutcomeAndTheEvaluationIsAudited() throws Exception {
        JsonNode r = post("/api/v1/evaluate", loan(17, "VERIFIED", 720, 80000, 200000, "en"));
        List<String> dispatches = new ArrayList<>();
        r.path("dispatches").forEach(d -> dispatches.add(d.path("type").asString() + ":" + d.path("status").asString()));
        assertThat(dispatches).contains("EMAIL:SENT", "PUSH:SENT");
        JsonNode log = get("/api/v1/evaluations/" + r.path("evaluationId").asString() + "/dispatches");
        assertThat(log.size()).isEqualTo(2);
        assertThat(get("/api/v1/evaluations?limit=5").get(0).path("action").asString()).isEqualTo("BLOCK");
        // a dry run neither logs nor dispatches
        JsonNode dry = post("/api/v1/evaluate", loan(17, "VERIFIED", 720, 80000, 200000, "en").replace("\"detailed\":true", "\"detailed\":true,\"dryRun\":true"));
        assertThat(dry.path("dispatches").size()).isZero();
        assertThat(get("/api/v1/evaluations/" + dry.path("evaluationId").asString() + "/dispatches").size()).isZero();
    }

    @Test
    void unknownTriggersTenantsAndModulesAreRefused() throws Exception {
        assertThat(post("/api/v1/evaluate", "{\"module\":\"LENDING\",\"trigger\":{\"form\":\"NOPE\",\"action\":\"SUBMIT\"}}")
                .path("_http").asInt()).isEqualTo(404);
        assertThat(post("/api/v1/evaluate", "{\"module\":\"ACCOUNTS\",\"groups\":[\"X\"]}").path("code").asString())
                .isEqualTo("MODULE_NOT_ENABLED");
    }
}
