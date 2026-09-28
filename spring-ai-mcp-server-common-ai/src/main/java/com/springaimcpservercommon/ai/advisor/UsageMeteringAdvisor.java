package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import java.util.Objects;

/**
 * Post-turn advisor that records token usage from the model's {@link ChatResponse} metadata (LLD-06 §4).
 *
 * <p>Order: {@link Ordered#LOWEST_PRECEDENCE} — runs last so the complete token counts are available
 * after tool calling rounds are done.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Reads {@link Usage#getPromptTokens()} / {@link Usage#getGenerationTokens()} from the response.</li>
 *   <li>Records Micrometer counters under {@code dynamic.ai.agent.tokens} tags:
 *       {@code agent}, {@code workspace}, {@code kind} (prompt|completion|total).</li>
 *   <li>Delegates to a {@link UsageSink} port so the persistence layer can update budget counters.</li>
 * </ul>
 *
 * <p>Failures in the sink are swallowed after logging — metering must not break the agent turn.
 */
@NullMarked
public final class UsageMeteringAdvisor implements CallAroundAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(UsageMeteringAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Port: persists token usage for budget accounting.
     *
     * <p>Implemented in the {@code persistence} module or a no-op default in {@code autoconfigure}.
     */
    @FunctionalInterface
    public interface UsageSink {
        /**
         * Records token usage for the given turn.
         *
         * @param principal        calling principal
         * @param agentId          agent that produced the usage
         * @param promptTokens     input tokens consumed
         * @param completionTokens output tokens produced
         */
        void record(DaiPrincipal principal, java.util.UUID agentId, long promptTokens, long completionTokens);
    }

    private final AgentDefinition agent;
    private final DaiPrincipal principal;
    private final UsageSink usageSink;
    private final ObservationRegistry observationRegistry;

    /**
     * Creates the metering advisor for a specific agent turn.
     *
     * @param agent               the agent definition governing this turn
     * @param principal           the calling principal
     * @param usageSink           port for persisting token usage
     * @param observationRegistry Micrometer observation registry
     */
    public UsageMeteringAdvisor(AgentDefinition agent, DaiPrincipal principal,
                                 UsageSink usageSink, ObservationRegistry observationRegistry) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);
        try {
            recordUsage(response.response());
        } catch (Exception e) {
            LOG.warn("Usage metering failed for agent {} principal {}; usage not recorded",
                    agent.slug(), principal.principalId(), e);
        }
        return response;
    }

    private void recordUsage(ChatResponse chatResponse) {
        if (chatResponse == null) return;
        var metadata = chatResponse.getMetadata();
        if (metadata == null) return;
        Usage usage = metadata.getUsage();
        if (usage == null) return;

        long prompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
        long completion = usage.getGenerationTokens() != null ? usage.getGenerationTokens() : 0L;
        long total = prompt + completion;

        if (total == 0) return;

        // Micrometer counters (meters: dynamic.ai.agent.tokens per ADR-0021 / CLAUDE.md namespace)
        io.micrometer.core.instrument.Metrics.counter("dynamic.ai.agent.tokens",
                "agent", agent.slug(),
                "workspace", agent.workspaceId().toString(),
                "kind", "prompt").increment(prompt);
        io.micrometer.core.instrument.Metrics.counter("dynamic.ai.agent.tokens",
                "agent", agent.slug(),
                "workspace", agent.workspaceId().toString(),
                "kind", "completion").increment(completion);
        io.micrometer.core.instrument.Metrics.counter("dynamic.ai.agent.tokens",
                "agent", agent.slug(),
                "workspace", agent.workspaceId().toString(),
                "kind", "total").increment(total);

        LOG.debug("Agent {} used {} prompt + {} completion = {} total tokens for principal {}",
                agent.slug(), prompt, completion, total, principal.principalId());

        usageSink.record(principal, agent.id(), prompt, completion);
    }
}
