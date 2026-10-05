package com.springaimcpservercommon.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the CEL rule engine (ADR-0025), bound under {@code dynamic.ai.agent.rule-engine}.
 *
 * @param enabled           switch the engine on (default off: it needs the {@code ruleengine} artifact and its
 *                          tables, and nothing is exposed by default)
 * @param pollInterval      how often a node checks the database change markers per tenant (1s..10m); other nodes
 *                          see a rule edit within this time
 * @param maxTenants        most tenants whose rules are kept in memory (1..100000)
 * @param defaultLanguage   language used when a message has no text in any language the caller asked for
 * @param recordEvaluations write one value-free row per group evaluation to {@code dai_re_evaluation}
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.rule-engine")
public record DaiRuleEngineProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10s") Duration pollInterval,
        @DefaultValue("500") int maxTenants,
        @DefaultValue("en") String defaultLanguage,
        @DefaultValue("true") boolean recordEvaluations) {

    /** Validates the settings. */
    public DaiRuleEngineProperties {
        if (pollInterval.compareTo(Duration.ofSeconds(1)) < 0 || pollInterval.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.poll-interval must be 1s..10m");
        }
        if (maxTenants < 1 || maxTenants > 100_000) {
            throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.max-tenants must be 1..100000");
        }
        if (defaultLanguage.isBlank()) {
            throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.default-language must not be blank");
        }
    }
}
