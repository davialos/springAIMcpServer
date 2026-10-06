package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Multi-tenant rule engine on Google CEL. Applications call {@code POST /api/v1/evaluate} from a trigger point (a form
 * action, optionally on one field); the engine evaluates the rule groups bound to it by their evaluation policy and
 * answers with the final messages, the action (allow, warn, block) and the raw rule results.
 */
@SpringBootApplication
@EnableConfigurationProperties(RuleEngineProperties.class)
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}
