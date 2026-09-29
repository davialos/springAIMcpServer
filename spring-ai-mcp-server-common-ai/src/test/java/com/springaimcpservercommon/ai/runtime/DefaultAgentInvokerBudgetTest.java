package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentChatRequest;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentInvocationException;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAgentInvokerBudgetTest {

    private final AtomicInteger modelResolutions = new AtomicInteger();
    private final AtomicInteger usageRecords = new AtomicInteger();

    private final AgentDefinition agent = new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "support-bot",
            "Support", "You help.", new ModelSelection("openai", "gpt-4o-mini", null, null, null), List.of(),
            MemorySpec.NONE, GuardrailSpec.OFF, LimitSpec.DEFAULT, OutputSpec.TEXT, Set.of(), "hash");

    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private DefaultAgentInvoker invoker(boolean withinBudget) {
        return new DefaultAgentInvoker(
                (selection, p) -> {
                    modelResolutions.incrementAndGet();
                    throw new AssertionError("the model must not be resolved for a refused turn");
                },
                null,
                () -> {
                    throw new AssertionError("the catalog must not be read for a refused turn");
                },
                agentId -> true,
                (a, p) -> withinBudget,
                (a, p, prompt, completion) -> usageRecords.incrementAndGet(),
                ObservationRegistry.NOOP,
                new InMemoryChatMemory(),
                null);
    }

    private final AgentChatRequest request = new AgentChatRequest(null, "hello", "req-1");

    @Test
    void syncTurnIsRefusedWithBudgetExhaustedBeforeAnyModelWork() {
        assertThatThrownBy(() -> invoker(false).invoke(agent, request, principal, null))
                .isInstanceOfSatisfying(AgentInvocationException.class, e -> {
                    assertThat(e.code()).isEqualTo("budget-exhausted");
                    assertThat(e.retryable()).isFalse();
                });
        assertThat(modelResolutions).hasValue(0);
        assertThat(usageRecords).hasValue(0);
    }

    @Test
    void streamedTurnEmitsOnlyANonRetryableBudgetErrorEvent() {
        List<StreamEvent> events = invoker(false).stream(agent, request, principal, null).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(StreamEvent.ErrorEvent.class, e -> {
            assertThat(e.code()).isEqualTo("budget-exhausted");
            assertThat(e.retryable()).isFalse();
        });
        assertThat(modelResolutions).hasValue(0);
    }
}
