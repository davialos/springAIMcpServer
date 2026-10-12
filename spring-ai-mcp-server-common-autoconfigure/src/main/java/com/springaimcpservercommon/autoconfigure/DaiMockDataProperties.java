package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.mockdata.ai.FallbackMockDataEngine;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Settings of the mock-data engine and the load simulator (ADR-0031), bound under {@code dynamic.ai.agent.mockdata}.
 *
 * @param enabled   switch the mock-data beans off (default on once the artifact is present)
 * @param mode      {@code FAKER}: Datafaker only; {@code AI_FALLBACK} (default): the AI model fills gaps and {@code @FakeAi}
 *                  fields when a model is configured; {@code AI}: the model fills every field without a Datafaker annotation
 * @param locale    default locale tag of generated text
 * @param seed      seed for reproducible values, or unset
 * @param ai        AI pool settings
 * @param simulator virtual-thread load simulator
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.mockdata")
public record DaiMockDataProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("AI_FALLBACK") FallbackMockDataEngine.Mode mode,
        @DefaultValue("en") String locale,
        @Nullable Long seed,
        @DefaultValue Ai ai,
        @DefaultValue Simulator simulator) {

    /**
     * How the AI model is used: in batches whose results are pooled and reused, within a call budget.
     *
     * @param batchSize        objects per model call
     * @param maxCalls         model calls allowed per application run
     * @param failureThreshold consecutive failures that pause the model
     * @param cooldown         length of the pause
     * @param timeout          timeout of one model call
     */
    public record Ai(@DefaultValue("20") int batchSize, @DefaultValue("50") int maxCalls,
                     @DefaultValue("3") int failureThreshold, @DefaultValue("1m") Duration cooldown,
                     @DefaultValue("60s") Duration timeout) {
    }

    /**
     * The load simulator. Off by default; never runs when production rules apply (PROD or an undeclared tier).
     *
     * @param enabled            create the {@code LoadSimulator} bean
     * @param allowedHosts       hosts a simulation may target besides loopback ({@code api.test.example.com}, {@code *.stage.example.com})
     * @param allowLoopback      allow localhost targets
     * @param maxVirtualUsers    most virtual users per simulation
     * @param maxDuration        longest simulation
     * @param maxRequestsPerSecond highest request rate a simulation may ask for; 0 = no cap required
     */
    public record Simulator(@DefaultValue("false") boolean enabled, @DefaultValue List<String> allowedHosts,
                            @DefaultValue("true") boolean allowLoopback, @DefaultValue("200") int maxVirtualUsers,
                            @DefaultValue("10m") Duration maxDuration, @DefaultValue("0") int maxRequestsPerSecond) {
    }
}
