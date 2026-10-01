package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessScopeValidatorTest {

    private final BusinessScopeValidator validator = new BusinessScopeValidator();
    private final EffectiveCatalog catalog = GuardFixtures.shopCatalog(1);

    private PromptVerdict judge(String prompt) {
        return validator.validate(GuardFixtures.request(prompt, GuardFixtures.scopeOnly(), catalog));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Show me the open orders of customer 42",
            "Which invoices are overdue?",
            "How many purchases shipped yesterday?",
            "List gold loyalty tier clients",
            "What is the grand total of order 7781 including tax?",
            "Which buyers have unpaid billing items?",
            "Write a short summary of the cancelled shipments"
    })
    void allowsPromptsAboutTheDomain(String prompt) {
        assertThat(judge(prompt).allowed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What will the weather be in Paris tomorrow?",
            "Write a poem about love and flowers",
            "Who won the football world cup in 2018?",
            "Give me a recipe for chocolate cake with strawberries",
            "Explain quantum entanglement to a physics student"
    })
    void rejectsPromptsOutsideTheDomain(String prompt) {
        assertThat(judge(prompt)).isInstanceOfSatisfying(PromptVerdict.Rejected.class, r -> {
            assertThat(r.code()).isEqualTo("off_topic");
            assertThat(r.message()).contains("Customer", "Order");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"hi", "thanks!", "and the second one?", "yes please", "ok"})
    void doesNotJudgeGreetingsAndShortFollowUps(String prompt) {
        assertThat(judge(prompt).allowed()).isTrue();
    }

    @Test
    void scopeKeywordsAndTopicAllowListWidenTheDomain() {
        InputValidationPolicy withKeywords = new InputValidationPolicy(false, true, 0.25, 2,
                List.of("weather", "forecast"));

        assertThat(validator.validate(GuardFixtures.request("What will the weather be in Paris tomorrow?",
                withKeywords, catalog)).allowed()).isTrue();
        assertThat(validator.validate(GuardFixtures.request("Give me a recipe for chocolate cake",
                GuardFixtures.scopeOnly(), catalog, List.of("recipe"))).allowed()).isTrue();
    }

    @Test
    void theOffTopicMessageNamesTheAllowListWhenThereIsOne() {
        PromptVerdict verdict = validator.validate(GuardFixtures.request("Who won the football world cup?",
                GuardFixtures.scopeOnly(), catalog, List.of("orders", "invoices")));

        assertThat(verdict).isInstanceOfSatisfying(PromptVerdict.Rejected.class,
                r -> assertThat(r.message()).endsWith("It can help with: orders, invoices."));
    }

    @Test
    void doesNothingWhenScopeCheckIsOff() {
        assertThat(validator.validate(GuardFixtures.request("Write a poem about love and flowers",
                GuardFixtures.threatsOnly(), catalog)).allowed()).isTrue();
    }

    @Test
    void theVocabularyIsBuiltOncePerCatalogGeneration() {
        BusinessVocabulary first = validator.vocabulary(catalog);

        assertThat(validator.vocabulary(catalog)).isSameAs(first);
        assertThat(validator.vocabulary(GuardFixtures.shopCatalog(2))).isNotSameAs(first);
    }

    @Test
    void theCatalogIsNotReadWhenTheCheckIsOff() {
        PromptValidationRequest request = new PromptValidationRequest("anything", java.util.UUID.randomUUID(),
                "agent", GuardFixtures.PRINCIPAL, GuardFixtures.threatsOnly(), List.of(), () -> {
                    throw new AssertionError("catalog must not be read");
                });

        assertThat(validator.validate(request).allowed()).isTrue();
    }
}
