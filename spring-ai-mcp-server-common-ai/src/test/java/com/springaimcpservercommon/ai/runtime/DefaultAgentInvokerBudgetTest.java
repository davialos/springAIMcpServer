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
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

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
    private final List<ConversationRecorder.Exchange> exchanges = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<TurnRecorder.TurnRecord> recorded = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final AgentDefinition agent = new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "support-bot",
            "Support", "You help.", new ModelSelection("openai", "gpt-4o-mini", null, null, null), List.of(),
            MemorySpec.NONE, GuardrailSpec.OFF, LimitSpec.DEFAULT, OutputSpec.TEXT, Set.of(), "hash");

    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private ObservationRegistry observations = ObservationRegistry.NOOP;

    private DefaultAgentInvoker invoker(boolean withinBudget) {
        return invoker(withinBudget, recorded::add);
    }

    private DefaultAgentInvoker invoker(boolean withinBudget, TurnRecorder recorder) {
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
                recorder,
                exchanges::add,
                observations,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build(),
                null);
    }

    private final UUID turnId = UUID.randomUUID();
    private final AgentChatRequest request = new AgentChatRequest(null, "hello", "req-1", turnId);

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
            assertThat(e.turnId()).isEqualTo(turnId);
        });
        assertThat(modelResolutions).hasValue(0);
    }

    @Test
    void aRequestWithoutATurnIdStillGetsAGeneratedOneOnStreamErrors() {
        AgentChatRequest anonymous = new AgentChatRequest(null, "hello", "req-2");

        List<StreamEvent> events = invoker(false).stream(agent, anonymous, principal, null).collectList().block();

        assertThat(events).singleElement().isInstanceOfSatisfying(StreamEvent.ErrorEvent.class,
                e -> assertThat(e.turnId()).isNotNull().isNotEqualTo(turnId));
    }

    @Test
    void aRefusedSyncTurnIsRecordedAsRejectedByBudgetWithTheClientTurnId() {
        assertThatThrownBy(() -> invoker(false).invoke(agent, request, principal, null))
                .isInstanceOf(AgentInvocationException.class);

        assertThat(recorded).singleElement().satisfies(t -> {
            assertThat(t.turnId()).isEqualTo(turnId);
            assertThat(t.outcome()).isEqualTo(TurnRecorder.Outcome.REJECTED);
            assertThat(t.finish()).isEqualTo(TurnRecorder.Finish.BUDGET);
            assertThat(t.errorCode()).isEqualTo("budget-exhausted");
            assertThat(t.streaming()).isFalse();
            assertThat(t.channel()).isEqualTo(com.springaimcpservercommon.core.invocation.Channel.CHAT);
            assertThat(t.agent()).isEqualTo(agent);
            assertThat(t.principal()).isEqualTo(principal);
            assertThat(t.endedAt()).isAfterOrEqualTo(t.startedAt());
            assertThat(t.modelCallId()).isNotNull();
        });
    }

    @Test
    void aRefusedStreamedTurnIsRecordedOnceWithTheRequestedChannel() {
        AgentChatRequest viaEndpoint = new AgentChatRequest(null, "hello", "req-3", turnId,
                com.springaimcpservercommon.core.invocation.Channel.ENDPOINT);

        invoker(false).stream(agent, viaEndpoint, principal, null).collectList().block();

        assertThat(recorded).singleElement().satisfies(t -> {
            assertThat(t.turnId()).isEqualTo(turnId);
            assertThat(t.outcome()).isEqualTo(TurnRecorder.Outcome.REJECTED);
            assertThat(t.streaming()).isTrue();
            assertThat(t.channel()).isEqualTo(com.springaimcpservercommon.core.invocation.Channel.ENDPOINT);
        });
    }

    @Test
    void aFailingRecorderNeverChangesTheOutcomeOfATurn() {
        TurnRecorder broken = turn -> {
            throw new IllegalStateException("store down");
        };

        assertThatThrownBy(() -> invoker(false, broken).invoke(agent, request, principal, null))
                .isInstanceOfSatisfying(AgentInvocationException.class,
                        e -> assertThat(e.code()).isEqualTo("budget-exhausted"));
        List<StreamEvent> events = invoker(false, broken).stream(agent, request, principal, null)
                .collectList().block();
        assertThat(events).hasSize(1);
    }

    @Test
    void refusedTurnsNeverReachTheConversationRecorder() {
        assertThatThrownBy(() -> invoker(false).invoke(agent, request, principal, null))
                .isInstanceOf(AgentInvocationException.class);
        invoker(false).stream(agent, request, principal, null).collectList().block();

        assertThat(exchanges).isEmpty();
    }

    @Test
    void everyTurnIsTracedAsOneSpanWithItsOutcomeAndIds() {
        java.util.List<io.micrometer.observation.Observation.Context> stopped =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context context) {
                stopped.add(context);
            }
        });

        assertThatThrownBy(() -> invoker(false).invoke(agent, request, principal, null))
                .isInstanceOf(AgentInvocationException.class);
        invoker(false).stream(agent, request, principal, null).collectList().block();

        assertThat(stopped).hasSize(2).allSatisfy(c -> {
            assertThat(c.getName()).isEqualTo("dynamic.ai.agent.turn");
            assertThat(c.getContextualName()).isEqualTo("dai.agent.turn");
            assertThat(c.getLowCardinalityKeyValue("dai.agent.slug").getValue()).isEqualTo("support-bot");
            assertThat(c.getLowCardinalityKeyValue("dai.turn.outcome").getValue()).isEqualTo("REJECTED");
            assertThat(c.getLowCardinalityKeyValue("dai.turn.error_code").getValue()).isEqualTo("budget-exhausted");
            assertThat(c.getHighCardinalityKeyValue("dai.turn.id").getValue()).isEqualTo(turnId.toString());
            assertThat(c.getHighCardinalityKeyValue("dai.model_call.id")).isNotNull();
        });
        assertThat(stopped.stream().map(c -> c.getLowCardinalityKeyValue("dai.streaming").getValue()))
                .containsExactlyInAnyOrder("false", "true");
    }
}
