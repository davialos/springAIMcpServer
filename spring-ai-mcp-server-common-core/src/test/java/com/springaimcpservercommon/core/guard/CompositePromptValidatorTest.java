package com.springaimcpservercommon.core.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CompositePromptValidatorTest {

    private final PromptValidationRequest request = GuardFixtures.request("show open orders",
            GuardFixtures.threatsOnly(), GuardFixtures.shopCatalog(1));

    @Test
    void theFirstRejectionWinsAndLaterValidatorsDoNotRun() {
        AtomicInteger later = new AtomicInteger();
        CompositePromptValidator composite = new CompositePromptValidator(List.<PromptValidator>of(
                r -> PromptVerdict.allow(),
                r -> PromptVerdict.reject("regulated_advice", "No investment advice.", List.of("rule-7")),
                r -> {
                    later.incrementAndGet();
                    return PromptVerdict.allow();
                }));

        assertThat(composite.validate(request)).isInstanceOfSatisfying(PromptVerdict.Rejected.class,
                r -> assertThat(r.code()).isEqualTo("regulated_advice"));
        assertThat(later).hasValue(0);
    }

    @Test
    void aValidatorThatThrowsRejectsThePrompt() {
        CompositePromptValidator composite = new CompositePromptValidator(List.<PromptValidator>of(r -> {
            throw new IllegalStateException("moderation service down");
        }));

        assertThat(composite.validate(request)).isInstanceOfSatisfying(PromptVerdict.Rejected.class,
                r -> assertThat(r.code()).isEqualTo("input_validation_failed"));
    }

    @Test
    void theDefaultsRunThreatDetectionThenScope() {
        assertThat(CompositePromptValidator.defaults().validators())
                .hasExactlyElementsOfTypes(MaliciousPromptValidator.class, BusinessScopeValidator.class);
        assertThat(CompositePromptValidator.defaults().validate(request).allowed()).isTrue();
    }

    @Test
    void policiesCombineToTheStrictest() {
        InputValidationPolicy agent = new InputValidationPolicy(false, true, 0.1, 3, List.of("billing"));
        InputValidationPolicy host = new InputValidationPolicy(true, false, 0.3, 2, List.of("tax"));

        InputValidationPolicy effective = agent.strictest(host);

        assertThat(effective.threatDetection()).isTrue();
        assertThat(effective.businessScope()).isTrue();
        assertThat(effective.minRelevance()).isEqualTo(0.3);
        assertThat(effective.minTermsToJudge()).isEqualTo(2);
        assertThat(effective.scopeKeywords()).containsExactly("billing", "tax");
    }
}
