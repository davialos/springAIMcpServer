package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.OrderOverride;
import com.springaimcpservercommon.validation.Scope;
import com.springaimcpservercommon.validation.ValidationRule;
import com.springaimcpservercommon.validation.Validator;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Context-aware validation (ADR-0027). When the {@code spring-ai-mcp-server-common-validation} artifact is on the
 * class path, every {@link ValidationRule} and {@link OrderOverride} bean in the host, plus the overrides declared
 * under {@code dynamic.ai.agent.validation.overrides}, are collected into one {@link Validator} bean that can be
 * injected and called at any stage. Declare your own {@link Validator} bean to replace it. With
 * {@code dynamic.ai.agent.validation.web.enabled=true} the host's {@code @RequestBody} arguments are validated too.
 */
@NullMarked
@AutoConfiguration
@ConditionalOnClass(Validator.class)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.validation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(DaiValidationProperties.class)
public class DaiValidationAutoConfiguration {

    /**
     * The validator over all rule and override beans and configured overrides.
     *
     * @param rules      rule beans
     * @param overrides  ordering override beans
     * @param properties validation settings
     * @return the validator
     */
    @Bean
    @ConditionalOnMissingBean
    public Validator validator(ObjectProvider<ValidationRule<?>> rules, ObjectProvider<OrderOverride> overrides,
                               DaiValidationProperties properties) {
        List<OrderOverride> all = new ArrayList<>(overrides.orderedStream().toList());
        for (DaiValidationProperties.Override o : properties.overrides()) {
            Scope scope = Scope.of(o.stage(), o.state(), o.endpoint(), o.action());
            if (o.skip()) {
                all.add(OrderOverride.skip(o.rule(), scope));
            } else if (o.order() != null) {
                all.add(OrderOverride.reorder(o.rule(), scope, o.order()));
            } else {
                throw new IllegalStateException(
                        "dynamic.ai.agent.validation.overrides: '" + o.rule() + "' needs an order or skip=true");
            }
        }
        return Validator.builder().rules(rules.orderedStream().toList()).overrides(all).build();
    }

    /** Opt-in Spring MVC hook (controller stage). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter")
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.validation.web", name = "enabled", havingValue = "true")
    static class Web {

        @Bean
        @ConditionalOnMissingBean
        ValidationContextResolver validationContextResolver() {
            return ValidationContextResolver.defaults();
        }

        @Bean
        ValidatingRequestBodyAdvice validatingRequestBodyAdvice(Validator validator,
                                                                ValidationContextResolver resolver) {
            return new ValidatingRequestBodyAdvice(validator, resolver);
        }

        @Bean
        ValidationProblemAdvice validationProblemAdvice() {
            return new ValidationProblemAdvice();
        }
    }
}
