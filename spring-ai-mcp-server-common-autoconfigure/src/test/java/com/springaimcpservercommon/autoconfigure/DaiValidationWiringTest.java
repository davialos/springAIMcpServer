package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.Rules;
import com.springaimcpservercommon.validation.ValidationContext;
import com.springaimcpservercommon.validation.ValidationRule;
import com.springaimcpservercommon.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DaiValidationWiringTest {

    record Order(int qty) {
    }

    @Configuration
    static class Host {
        @Bean
        ValidationRule<Order> positive() {
            return Rules.forType(Order.class).id("positive").order(2).check(o -> o.qty() > 0, "qty", "qty > 0");
        }

        @Bean
        ValidationRule<Order> other() {
            return Rules.forType(Order.class).id("other").order(1).check(o -> true, null, "x");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DaiValidationAutoConfiguration.class))
            .withUserConfiguration(Host.class);

    private static final ValidationContext CTX = ValidationContext.of("SERVICE", "DRAFT", "POST /orders", "SUBMIT");

    @Test
    void collectsRuleBeansAndPropertyOverrides() {
        runner.withPropertyValues(
                        "dynamic.ai.agent.validation.overrides[0].rule=positive",
                        "dynamic.ai.agent.validation.overrides[0].action=SUBMIT",
                        "dynamic.ai.agent.validation.overrides[0].order=0",
                        "dynamic.ai.agent.validation.overrides[1].rule=other",
                        "dynamic.ai.agent.validation.overrides[1].state=APPROVED",
                        "dynamic.ai.agent.validation.overrides[1].skip=true")
                .run(ctx -> {
                    Validator v = ctx.getBean(Validator.class);
                    assertThat(v.explain(new Order(1), CTX)).containsExactly("positive", "other");
                    assertThat(v.explain(new Order(1), CTX.withState("APPROVED"))).containsExactly("positive");
                });
    }

    @Test
    void overrideWithoutOrderOrSkipFailsStartup() {
        runner.withPropertyValues("dynamic.ai.agent.validation.overrides[0].rule=positive")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void disabledAndWebOffByDefault() {
        runner.withPropertyValues("dynamic.ai.agent.validation.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(Validator.class));
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ValidatingRequestBodyAdvice.class));
    }

    @Test
    void webHookOptIn() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DaiValidationAutoConfiguration.class))
                .withUserConfiguration(Host.class)
                .withPropertyValues("dynamic.ai.agent.validation.web.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ValidatingRequestBodyAdvice.class)
                        .hasSingleBean(ValidationProblemAdvice.class));
    }
}
