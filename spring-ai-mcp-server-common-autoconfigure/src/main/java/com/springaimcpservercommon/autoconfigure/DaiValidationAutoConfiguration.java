package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.OrderOverride;
import com.springaimcpservercommon.validation.ValidationRule;
import com.springaimcpservercommon.validation.Validator;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Context-aware validation (ADR-0027). When the {@code spring-ai-mcp-server-common-validation} artifact is on the
 * class path, every {@link ValidationRule} and {@link OrderOverride} bean in the host is collected into one
 * {@link Validator} bean that can be injected and called at any stage. Declare your own {@link Validator} bean to
 * replace it.
 */
@NullMarked
@AutoConfiguration
@ConditionalOnClass(Validator.class)
public class DaiValidationAutoConfiguration {

    /**
     * The validator over all rule and override beans.
     *
     * @param rules     rule beans
     * @param overrides ordering override beans
     * @return the validator
     */
    @Bean
    @ConditionalOnMissingBean
    public Validator validator(ObjectProvider<ValidationRule<?>> rules, ObjectProvider<OrderOverride> overrides) {
        return Validator.builder()
                .rules(rules.orderedStream().toList())
                .overrides(overrides.orderedStream().toList())
                .build();
    }
}
