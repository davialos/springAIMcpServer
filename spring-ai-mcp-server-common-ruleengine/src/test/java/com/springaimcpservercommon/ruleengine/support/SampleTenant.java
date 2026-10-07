package com.springaimcpservercommon.ruleengine.support;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.ChannelTrigger;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.EmailTemplate;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.GroupRule;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.model.Rule;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.store.TenantData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Java mirror of {@code scripts/rule-engine/sample-data.sql} (same ids), so the unit tests and the PostgreSQL
 * integration test can run the same scenarios.
 */
public final class SampleTenant {

    public static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SampleTenant() {
    }

    public static UUID id(String prefix, int n) {
        return UUID.fromString("%s-0000-0000-0000-%012x".formatted(prefix, n));
    }

    public static List<Parameter> parameters() {
        return List.of(
                p(1, "customer", "age", DataType.INT), p(2, "customer", "country", DataType.STRING),
                p(3, "customer", "kycStatus", DataType.STRING), p(4, "customer", "creditScore", DataType.INT),
                p(5, "customer", "email", DataType.STRING), p(6, "loan", "amount", DataType.DOUBLE),
                p(7, "loan", "tenureMonths", DataType.INT), p(8, "order", "total", DataType.DOUBLE),
                p(9, "order", "itemCount", DataType.INT));
    }

    private static Parameter p(int n, String object, String attribute, DataType type) {
        return new Parameter(id("a1a1a1a1", n), object, attribute, type, true);
    }

    private static UUID b(int n) {
        return id("cccccccc", n);
    }

    public static Map<UUID, Map<String, String>> messages() {
        return Map.ofEntries(
                bundle(1, "Customer meets the minimum age.", "ग्राहक न्यूनतम आयु आवश्यकता पूरी करता है।", "ลูกค้ามีอายุตามเกณฑ์ขั้นต่ำ"),
                bundle(2, "Customer must be at least 18 years old.", "ग्राहक की आयु कम से कम 18 वर्ष होनी चाहिए।", "ลูกค้าต้องมีอายุอย่างน้อย 18 ปี"),
                bundle(3, "KYC is verified.", "केवाईसी सत्यापित है।", "ยืนยัน KYC แล้ว"),
                bundle(4, "KYC verification is pending or failed.", "केवाईसी सत्यापन लंबित है या विफल रहा।", "การยืนยัน KYC ยังไม่เสร็จหรือไม่ผ่าน"),
                bundle(5, "Credit score is acceptable.", "क्रेडिट स्कोर स्वीकार्य है।", "คะแนนเครดิตอยู่ในเกณฑ์"),
                bundle(6, "Credit score is below the required 650.", "क्रेडिट स्कोर आवश्यक 650 से कम है।", "คะแนนเครดิตต่ำกว่าเกณฑ์ 650"),
                bundle(7, "Loan amount is within the allowed limit.", "ऋण राशि अनुमत सीमा के भीतर है।", "จำนวนเงินกู้อยู่ในวงเงินที่อนุญาต"),
                bundle(8, "Loan amount exceeds the limit allowed for this credit score.", "ऋण राशि इस क्रेडिट स्कोर के लिए अनुमत सीमा से अधिक है।", "จำนวนเงินกู้เกินวงเงินที่อนุญาตตามคะแนนเครดิต"),
                bundle(9, "High-value loan: manual review is recommended.", "उच्च मूल्य का ऋण: मैन्युअल समीक्षा अनुशंसित है।", "สินเชื่อมูลค่าสูง: แนะนำให้ตรวจสอบด้วยตนเอง"),
                bundle(10, "All eligibility checks passed.", "सभी पात्रता जाँच सफल रहीं।", "ผ่านการตรวจสอบคุณสมบัติทั้งหมด"),
                bundle(11, "Loan eligibility checks failed.", "ऋण पात्रता जाँच विफल रही।", "การตรวจสอบคุณสมบัติสินเชื่อไม่ผ่าน"),
                bundle(12, "Loan application blocked", "ऋण आवेदन रोका गया", "คำขอสินเชื่อถูกระงับ"),
                bundle(13, "Your loan application did not pass the eligibility checks.", "आपका ऋण आवेदन पात्रता जाँच में सफल नहीं हुआ।", "คำขอสินเชื่อของคุณไม่ผ่านการตรวจสอบคุณสมบัติ"));
    }

    private static Map.Entry<UUID, Map<String, String>> bundle(int n, String en, String hi, String th) {
        return Map.entry(b(n), Map.of("en", en, "hi", hi, "th", th));
    }

    private static Rule rule(int n, String code, String name, String expression, Integer trueMsg, Integer falseMsg,
                             Action trueAction, Action falseAction) {
        return new Rule(id("dddddddd", n), code, name, expression, trueMsg == null ? null : b(trueMsg),
                falseMsg == null ? null : b(falseMsg), trueAction, falseAction);
    }

    public static List<Rule> rules() {
        return List.of(
                rule(1, "ADULT", "Customer is an adult", "customer.age >= 18", 1, 2, Action.ALLOW, Action.BLOCK),
                rule(2, "KYC_VERIFIED", "KYC is verified", "customer.kycStatus == \"VERIFIED\"", 3, 4, Action.ALLOW, Action.BLOCK),
                rule(3, "CREDIT_SCORE_MIN", "Credit score at least 650", "customer.creditScore >= 650", 5, 6, Action.ALLOW, Action.BLOCK),
                rule(4, "AMOUNT_WITHIN_LIMIT", "Amount within score-based limit",
                        "loan.amount <= double(customer.creditScore) * 1000.0", 7, 8, Action.ALLOW, Action.BLOCK),
                rule(5, "HIGH_VALUE_REVIEW", "Loan under 500000 needs no review", "loan.amount < 500000.0", null, 9,
                        Action.ALLOW, Action.WARN));
    }

    private static RuleGroup group(int n, String code, EvaluationPolicy policy, MatchOn matchOn, Integer trueMsg,
                                   Integer falseMsg) {
        List<GroupRule> members = new java.util.ArrayList<>();
        List<Rule> rules = rules();
        for (int i = 0; i < rules.size(); i++) {
            members.add(new GroupRule(rules.get(i), (i + 1) * 10));
        }
        return new RuleGroup(id("eeeeeeee", n), null, "LOAN", code, code, policy, matchOn,
                trueMsg == null ? null : b(trueMsg), falseMsg == null ? null : b(falseMsg), Action.ALLOW,
                Action.BLOCK, Action.BLOCK, members);
    }

    public static TenantData tenantData() {
        UUID template = id("f0f0f0f0", 1);
        UUID devApi = id("f1f1f1f1", 1);
        UUID qaApi = id("f1f1f1f1", 2);
        UUID prodApi = id("f1f1f1f1", 3);
        UUID composite = id("eeeeeeee", 1);
        List<RuleGroup> groups = List.of(
                group(1, "LOAN_ELIGIBILITY", EvaluationPolicy.COMPOSITE, MatchOn.TRUE, 10, 11),
                group(2, "LOAN_FIRST_FAILURE", EvaluationPolicy.FIRST_MATCH, MatchOn.FALSE, null, null),
                group(3, "LOAN_ALL_FAILURES", EvaluationPolicy.ALL_MATCH, MatchOn.FALSE, null, null),
                group(4, "LOAN_FULL_REPORT", EvaluationPolicy.EVALUATE_ALL, MatchOn.TRUE, null, null));
        List<ChannelBinding> channels = List.of(
                new ChannelBinding(id("c0c0c0c0", 1), OwnerType.GROUP, composite, ChannelTrigger.FALSE, ChannelType.EMAIL,
                        10, template, null, null, null, "customer.email"),
                new ChannelBinding(id("c0c0c0c0", 2), OwnerType.GROUP, composite, ChannelTrigger.FALSE, ChannelType.PUSH,
                        20, null, null, b(12), b(13), "customer.email"),
                new ChannelBinding(id("c0c0c0c0", 3), OwnerType.GROUP, composite, ChannelTrigger.ANY, ChannelType.API,
                        30, null, devApi, null, null, null));
        List<TriggerPoint> triggers = List.of(
                new TriggerPoint(id("77777777", 1), null, "loan-portal", TriggerType.FORM_ACTION, "LOAN_APPLICATION",
                        "SUBMIT", null, composite, 0),
                new TriggerPoint(id("77777777", 2), null, "loan-portal", TriggerType.FORM_ACTION, "LOAN_APPLICATION",
                        "APPROVE", null, id("eeeeeeee", 4), 0),
                new TriggerPoint(id("77777777", 3), null, "loan-portal", TriggerType.FORM_FIELD, "LOAN_APPLICATION",
                        "ON_CHANGE", "amount", id("eeeeeeee", 2), 0));
        return new TenantData(TENANT, groups, triggers, channels,
                Map.of(template, new EmailTemplate("TPL-1001", "Loan application declined")),
                Map.of(devApi, new ApiEndpoint(devApi, "loan-decision-webhook-dev", "POST", "http://localhost:8081/hooks/loan-decision",
                                ApiEnvironment.DEV, 3000, null, null, null),
                        qaApi, new ApiEndpoint(qaApi, "loan-decision-webhook-qa", "POST", "https://qa.example.test/hooks/loan-decision",
                                ApiEnvironment.QA, 3000, null, null, null),
                        prodApi, new ApiEndpoint(prodApi, "loan-decision-webhook-prod", "POST", "https://api.example.test/hooks/loan-decision",
                                ApiEnvironment.PROD, 3000, null, null, null)));
    }
}
