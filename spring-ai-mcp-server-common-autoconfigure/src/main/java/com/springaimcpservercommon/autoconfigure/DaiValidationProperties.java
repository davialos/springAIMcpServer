package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Settings of the context-aware validator (ADR-0027), bound under {@code dynamic.ai.agent.validation}.
 *
 * @param enabled   switch the validator bean off (default on once the artifact is present)
 * @param overrides ordering overrides declared in configuration, appended after override beans
 * @param web       Spring MVC request-body hook
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.validation")
public record DaiValidationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<Override> overrides,
        @DefaultValue Web web) {

    /**
     * One override: rule id pattern plus the scope (each blank = any) and either a new order or {@code skip}.
     *
     * @param rule     rule id pattern ({@code *} wildcard)
     * @param stage    stage pattern
     * @param state    state pattern
     * @param endpoint endpoint pattern, e.g. {@code POST /orders/*}
     * @param action   action pattern
     * @param order    order to use in that scope (ignored when {@code skip})
     * @param skip     do not run the rule in that scope
     */
    public record Override(String rule, @Nullable String stage, @Nullable String state, @Nullable String endpoint,
                           @Nullable String action, @Nullable Integer order, @DefaultValue("false") boolean skip) {
    }

    /**
     * Web hook settings.
     *
     * @param enabled validate {@code @RequestBody} arguments of the host's controllers at stage {@code CONTROLLER}
     *                and answer failures as 422 problem+json (default off: it touches the host's MVC pipeline)
     */
    public record Web(@DefaultValue("false") boolean enabled) {
    }
}
