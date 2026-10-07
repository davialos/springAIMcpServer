package com.springaimcpservercommon.ecosystem.ruleengine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The offline assistant is a router over the tool path and a set of templates: both are deterministic. */
class OfflineChatModelTest {

    private static String tool(String message) {
        var intent = OfflineChatModel.route(message);
        return intent == null ? null : intent.tool() + intent.args();
    }

    @Test
    void routesQuestionsToTheTheyAreAbout() {
        assertThat(tool("list the active rules")).isEqualTo("list_rules{status=ACTIVE}");
        assertThat(tool("which rules mention credit?")).isEqualTo("list_rules{}").doesNotStartWith("list_rule_groups");
        assertThat(tool("explain rule ADULT")).isEqualTo("get_rule{code=ADULT}");
        assertThat(tool("show me CREDIT_SCORE_MIN")).isEqualTo("get_rule{code=CREDIT_SCORE_MIN}");
        assertThat(tool("list the rule groups")).isEqualTo("list_rule_groups{}");
        assertThat(tool("show group LOAN_ELIGIBILITY")).isEqualTo("get_rule_group{code=LOAN_ELIGIBILITY}");
        assertThat(tool("which parameters are in the library?")).isEqualTo("list_library_parameters{}");
        assertThat(tool("check `customer.age >= 18`")).isEqualTo("check_cel_expression{expression=customer.age >= 18}");
        assertThat(tool("validate \"loan.amount < 5.0\"")).isEqualTo("check_cel_expression{expression=loan.amount < 5.0}");
    }

    @Test
    void aMessageItDoesNotUnderstandGetsHelpNotATool() {
        assertThat(OfflineChatModel.route("tell me a joke")).isNull();
        assertThat(OfflineChatModel.route("")).isNull();
    }

    @Test
    void anAnswerNamesTheDataAndSaysItIsNotALanguageModel() {
        String listing = OfflineChatModel.describe("list_rules", """
                {"tool":"list_rules","status":"ok","count":1,"data":[{"total":1,"truncated":false,"items":[
                {"code":"ADULT","name":"Customer is an adult","status":"ACTIVE","scope":"TENANT","expression":"customer.age >= 18"}]}]}""");
        assertThat(listing).contains("I found 1 rule").contains("**ADULT**").contains("`customer.age >= 18`");

        String empty = OfflineChatModel.describe("list_rules",
                "{\"tool\":\"list_rules\",\"status\":\"empty\",\"count\":1,\"data\":[{\"total\":0,\"truncated\":false,\"items\":[]}]}");
        assertThat(empty).contains("no matching rule");

        assertThat(OfflineChatModel.describe("check_cel_expression",
                "{\"status\":\"ok\",\"data\":[{\"valid\":false,\"error\":\"unknown parameter\",\"parameters\":[]}]}"))
                .contains("not valid").contains("unknown parameter");
        assertThat(OfflineChatModel.describe("list_rules", "{\"status\":\"not_permitted\"}")).contains("not allowed");
        assertThat(OfflineChatModel.describe("list_rules", "{\"status\":\"error\",\"error\":{\"code\":\"execution_error\"}}"))
                .contains("execution_error");
        assertThat(OfflineChatModel.describe("list_rules", "not json")).contains("could not read");
    }
}
