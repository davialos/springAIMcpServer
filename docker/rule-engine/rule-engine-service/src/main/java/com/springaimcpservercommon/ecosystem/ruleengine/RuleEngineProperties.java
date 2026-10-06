package com.springaimcpservercommon.ecosystem.ruleengine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Settings of the rule-engine service.
 *
 * @param jwtSecret         HMAC key the access tokens are verified with (at least 32 bytes)
 * @param environmentTier   DEV, TEST, STAGE or PROD; an unknown value is treated as PROD (LLD-12)
 * @param pollInterval      how often a node checks the rule change markers
 * @param maxTenants        tenant snapshots kept in memory
 * @param defaultLanguage   fallback message language
 * @param recordEvaluations write the value-free evaluation log
 * @param maxFacts          most facts one evaluation request may carry
 * @param maxBodyBytes      largest request body accepted
 */
@ConfigurationProperties(prefix = "ecosystem.rules")
public record RuleEngineProperties(String jwtSecret, String environmentTier, Duration pollInterval, int maxTenants,
                                   String defaultLanguage, boolean recordEvaluations, int maxFacts,
                                   long maxBodyBytes) {

    /** Refuses a signing key too short to be safe. */
    public RuleEngineProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ecosystem.rules.jwt-secret must be at least 32 bytes (set JWT_SECRET)");
        }
    }
}
