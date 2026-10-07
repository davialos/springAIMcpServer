package com.springaimcpservercommon.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

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
 * @param requireReview     a rule/group revision must be approved by a second person before it can be published
 *                          (default true; with false an author may publish a draft directly)
 * @param delivery          IMMEDIATE sends communications on the caller's thread; OUTBOX queues them in
 *                          {@code dai_re_dispatch} for a worker (retry with back-off, dead letters)
 * @param outbox            outbox worker tuning (used with {@code delivery=OUTBOX})
 * @param apiHosts          host patterns (with {@code *}) that identify DEV, QA and PROD APIs; a host that matches
 *                          none, or more than one, is EXTERNAL and needs a recorded confirmation
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.rule-engine")
public record DaiRuleEngineProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10s") Duration pollInterval,
        @DefaultValue("500") int maxTenants,
        @DefaultValue("en") String defaultLanguage,
        @DefaultValue("true") boolean recordEvaluations,
        @DefaultValue("true") boolean requireReview,
        @DefaultValue("IMMEDIATE") Delivery delivery,
        @DefaultValue Outbox outbox,
        @DefaultValue ApiHosts apiHosts) {

    /** How communications leave the engine. */
    public enum Delivery {
        /** Sent on the caller's thread, no retry. */
        IMMEDIATE,
        /** Queued in the PostgreSQL outbox and sent by a worker with retries. */
        OUTBOX
    }

    /**
     * Outbox worker settings.
     *
     * @param pollInterval       how often the worker looks for due rows (1s..5m)
     * @param batchSize          rows per run (1..500)
     * @param maxAttempts        attempts before a row is dead (1..50)
     * @param lease              how long a claimed row stays claimed (10s..1h)
     * @param baseDelay          delay after the first failure, doubling per attempt
     * @param maxDelay           cap of the back-off
     * @param deliveredRetention how long delivered rows (no personal data) are kept
     * @param deadRetention      how long dead rows are kept for operators
     */
    public record Outbox(
            @DefaultValue("5s") Duration pollInterval,
            @DefaultValue("20") int batchSize,
            @DefaultValue("6") int maxAttempts,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("10s") Duration baseDelay,
            @DefaultValue("1h") Duration maxDelay,
            @DefaultValue("1d") Duration deliveredRetention,
            @DefaultValue("14d") Duration deadRetention) {

        /** Validates the settings. */
        public Outbox {
            if (pollInterval.compareTo(Duration.ofSeconds(1)) < 0 || pollInterval.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.outbox.poll-interval must be 1s..5m");
            }
            if (batchSize < 1 || batchSize > 500) {
                throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.outbox.batch-size must be 1..500");
            }
            if (maxAttempts < 1 || maxAttempts > 50) {
                throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.outbox.max-attempts must be 1..50");
            }
            if (lease.compareTo(Duration.ofSeconds(10)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.outbox.lease must be 10s..1h");
            }
            if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.rule-engine.outbox base-delay must be > 0 and <= max-delay");
            }
        }
    }

    /**
     * Host patterns per API environment.
     *
     * @param dev  hosts of DEV APIs (default {@code localhost}, {@code 127.0.0.1})
     * @param qa   hosts of QA / test / staging APIs
     * @param prod hosts of production APIs
     */
    public record ApiHosts(
            @DefaultValue({"localhost", "127.0.0.1"}) List<String> dev,
            @DefaultValue List<String> qa,
            @DefaultValue List<String> prod) {
    }

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
