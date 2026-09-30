package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
 *   <li>Reads {@link Usage#getPromptTokens()} / {@link Usage#getCompletionTokens()} from the response.</li>
 *   <li>Records Micrometer counters under {@code dynamic.ai.agent.tokens} tags:
 *       {@code agent}, {@code workspace}, {@code kind} (prompt|completion|total).</li>
 *   <li>Delegates to a {@link UsageSink} port so the persistence layer can update budget counters.</li>
 * </ul>
 *
 * <p>Failures in the sink are swallowed after logging — metering must not break the agent turn.
 */
@NullMarked
public final class UsageMeteringAdvisor implements CallAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(UsageMeteringAdvisor.class);
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Port: persists token usage for budget accounting.
     *
     * <p>Implemented in {@code autoconfigure} on top of the usage ledger, with a no-op default. Called once
     * per completed model call, for both synchronous and streamed turns.
     */
    @FunctionalInterface
    public interface UsageSink {
        /**
         * Records token usage for the given turn.
         *
         * @param agent            agent that produced the usage (workspace, id and configured model)
         * @param principal        calling principal
         * @param promptTokens     input tokens consumed
         * @param completionTokens output tokens produced
         */
        void record(AgentDefinition agent, DaiPrincipal principal, long promptTokens, long completionTokens);
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
    public String getName() {
        return "daiUsageMetering";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        try {
            recordUsage(response.chatResponse());
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
        long completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;
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

        usageSink.record(agent, principal, prompt, completion);
    }
}
