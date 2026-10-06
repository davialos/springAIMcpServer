package com.springaimcpservercommon.ruleengine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Building a rule setup through the admin API: library, bundles, rules (compiled when saved), groups, trigger points,
 * channels and the API environment confirmation — then evaluating it.
 */
class AdminApiIT extends AbstractIT {

    private static List<String> texts(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.path("messages").forEach(m -> out.add(m.path("text").asString()));
        return out;
    }

    @Test
    void theLibraryIsCachedAndRefreshedWhenItChanges() throws Exception {
        JsonNode library = get("/api/v1/admin/library");
        assertThat(library.path("celNames").toString()).contains("customer.age", "transaction.amount");
        assertThat(post("/api/v1/admin/library/objects", "{\"code\":\"order\",\"name\":\"Order\",\"module\":\"PAYMENTS\"}")
                .path("_http").asInt()).isEqualTo(201);
        assertThat(post("/api/v1/admin/library/objects/order/attributes",
                "{\"code\":\"items\",\"name\":\"Items\",\"dataType\":\"INTEGER\",\"required\":true}").path("_http").asInt()).isEqualTo(201);
        assertThat(get("/api/v1/admin/library").path("celNames").toString()).contains("order.items");
        // a CEL variable name must start with a lower-case letter
        JsonNode bad = post("/api/v1/admin/library/objects", "{\"code\":\"Bad\",\"name\":\"Bad\"}");
        assertThat(bad.path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void rulesAreCompiledAgainstTheLibraryWhenTheyAreSaved() throws Exception {
        JsonNode check = post("/api/v1/admin/rules/validate", "{\"expression\":\"customer.age >= 18 && customer.height > 1\"}");
        assertThat(check.path("valid").asBoolean()).isFalse();
        assertThat(check.path("problems").get(0).asString()).isEqualTo("'customer.height' is not in the parameter library");
        JsonNode rejected = post("/api/v1/admin/rules", """
                {"code":"BAD","name":"Bad","module":"LENDING","expression":"customer.nope == 1"}""");
        assertThat(rejected.path("_http").asInt()).isEqualTo(422);
        assertThat(rejected.path("code").asString()).isEqualTo("INVALID_EXPRESSION");
    }

    @Test
    void aWholeSetupBuiltThroughTheApiEvaluatesInEveryLanguage() throws Exception {
        assertThat(post("/api/v1/admin/bundles", """
                {"code":"VIP_OK","description":"VIP customer","texts":{"en":"Welcome back, {customer.name}!","hi":"वापसी पर स्वागत है, {customer.name}!","th":"ยินดีต้อนรับกลับ {customer.name}!"}}""")
                .path("_http").asInt()).isEqualTo(201);
        assertThat(post("/api/v1/admin/bundles", "{\"code\":\"VIP_NO\",\"texts\":{\"en\":\"Not a VIP.\"}}").path("_http").asInt()).isEqualTo(201);
        JsonNode rule = post("/api/v1/admin/rules", """
                {"code":"VIP_RULE","name":"VIP","module":"LENDING","expression":"'vip' in customer.tags && customer.creditScore >= 700",
                 "trueBundle":"VIP_OK","falseBundle":"VIP_NO","trueAction":"ALLOW","falseAction":"WARN"}""");
        assertThat(rule.path("_http").asInt()).isEqualTo(201);
        assertThat(post("/api/v1/admin/rules/" + rule.path("id").asInt() + "/test",
                "{\"context\":{\"customer\":{\"tags\":[\"vip\"],\"creditScore\":710}}}").path("result").asBoolean()).isTrue();

        JsonNode group = post("/api/v1/admin/groups", """
                {"code":"VIP_CHECK","name":"VIP check","module":"LENDING","policy":"EVALUATE_ALL"}""");
        assertThat(group.path("_http").asInt()).isEqualTo(201);
        assertThat(send("PUT", "/api/v1/admin/groups/" + group.path("id").asInt() + "/members",
                "{\"rule\":\"VIP_RULE\",\"sequence\":1}", "ACME").get(0).path("rule").asString()).isEqualTo("VIP_RULE");
        assertThat(post("/api/v1/admin/triggers", """
                {"code":"VIP_VIEW","name":"Customer view","module":"LENDING","form":"CUSTOMER_PROFILE","action":"ADD","field":"segment"}""")
                .path("_http").asInt()).isEqualTo(201);
        assertThat(send("PUT", "/api/v1/admin/triggers/VIP_VIEW/groups", "{\"group\":\"VIP_CHECK\",\"sequence\":1}", "ACME")
                .get(0).path("code").asString()).isEqualTo("VIP_CHECK");

        String body = """
                {"module":"LENDING","trigger":{"form":"CUSTOMER_PROFILE","action":"add","field":"segment"},"language":"%s",
                 "context":{"customer":{"name":"Asha","tags":["vip"],"creditScore":720}}}""";
        assertThat(texts(post("/api/v1/evaluate", body.formatted("hi")))).containsExactly("वापसी पर स्वागत है, Asha!");
        assertThat(texts(post("/api/v1/evaluate", body.formatted("th")))).containsExactly("ยินดีต้อนรับกลับ Asha!");
        JsonNode no = post("/api/v1/evaluate", body.formatted("hi").replace("720", "500"));
        assertThat(no.path("action").asString()).isEqualTo("WARN");
        assertThat(texts(no)).as("no Hindi text for this bundle: the default language").containsExactly("Not a VIP.");
    }

    @Test
    void apiChannelsAreCheckedAgainstTheEnvironmentAndExternalApisNeedAConfirmation() throws Exception {
        // this system runs in DEV
        JsonNode sameEnv = post("/api/v1/admin/api-endpoints", "{\"name\":\"dev hook\",\"url\":\"https://crm.dev.acme.test/hook\"}");
        assertThat(sameEnv.path("_http").asInt()).isEqualTo(201);
        assertThat(sameEnv.path("environmentClass").asString()).isEqualTo("SAME_ENVIRONMENT");

        JsonNode qa = post("/api/v1/admin/api-endpoints", "{\"name\":\"qa hook\",\"url\":\"https://crm.qa.acme.test/hook\"}");
        assertThat(qa.path("_http").asInt()).isEqualTo(422);
        assertThat(qa.path("detail").asString()).contains("QA").contains("DEV");

        String external = "{\"name\":\"partner\",\"url\":\"https://hooks.partner.example/notify\"%s}";
        JsonNode ask = post("/api/v1/admin/api-endpoints", external.formatted(""));
        assertThat(ask.path("_http").asInt()).isEqualTo(409);
        assertThat(ask.path("confirmationRequired").asBoolean()).isTrue();
        assertThat(ask.path("detail").asString()).startsWith("You are trying to integrate an external API");
        JsonNode confirmed = post("/api/v1/admin/api-endpoints", external.formatted(",\"confirmExternal\":true,\"confirmedBy\":\"asha\""));
        assertThat(confirmed.path("_http").asInt()).isEqualTo(201);
        assertThat(confirmed.path("externalConfirmed").asBoolean()).isTrue();
        assertThat(confirmed.path("confirmedBy").asString()).isEqualTo("asha");

        JsonNode preview = get("/api/v1/admin/api-endpoints/check?url=https://elsewhere.example/x");
        assertThat(preview.path("confirmationRequired").asBoolean()).isTrue();
    }

    @Test
    void emailChannelsUseTheCallersTemplateAndTheModuleMustBeSelected() throws Exception {
        assertThat(post("/api/v1/admin/email-templates", "{\"templateId\":\"WELCOME_V3\",\"name\":\"Welcome mail\"}")
                .path("name").asString()).isEqualTo("Welcome mail");
        JsonNode channel = post("/api/v1/admin/channels",
                "{\"name\":\"Welcome e-mail\",\"type\":\"EMAIL\",\"templateId\":\"WELCOME_V3\",\"config\":{\"recipientPath\":\"customer.email\"}}");
        assertThat(channel.path("_http").asInt()).isEqualTo(201);
        assertThat(post("/api/v1/admin/channels", "{\"name\":\"x\",\"type\":\"EMAIL\"}").path("_http").asInt()).isEqualTo(400);
        // the tenant has not selected ACCOUNTS
        assertThat(post("/api/v1/admin/triggers", "{\"code\":\"TR1\",\"name\":\"T\",\"module\":\"ACCOUNTS\",\"form\":\"F\",\"action\":\"ADD\"}")
                .path("code").asString()).isEqualTo("MODULE_NOT_ENABLED");
        assertThat(send("PUT", "/api/v1/admin/tenant-modules", "{\"module\":\"ACCOUNTS\",\"enabled\":true}", "ACME").size()).isEqualTo(3);
    }
}
