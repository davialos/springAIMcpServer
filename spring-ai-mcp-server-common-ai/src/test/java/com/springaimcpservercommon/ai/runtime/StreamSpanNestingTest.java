package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentChatRequest;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A streamed turn has no stable thread, so its span can't be "current" in a ThreadLocal. Spring AI's stream path
 * takes its parent from the Reactor context instead; the turn observation must be written there so the provider's
 * spans of a streamed turn nest under {@code dai.agent.turn} (OQ-50).
 */
class StreamSpanNestingTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final List<String> stopped = new java.util.concurrent.CopyOnWriteArrayList<>();

    StreamSpanNestingTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context.getContextualName() != null ? context.getContextualName() : context.getName());
            }
        });
    }

    @Test
    void theModelsStreamSeesAnObservationWhoseAncestorIsTheTurnSpan() throws InterruptedException {
        java.util.concurrent.CountDownLatch recorded = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Observation> seenByModel = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.deferContextual(ctx -> {
                    seenByModel.set(ctx.getOrDefault("micrometer.observation", null));
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("hi")))));
                });
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        AgentDefinition agent = new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "bot", "Bot", "Help.",
                new ModelSelection("openai", "m", null, null, null), List.of(), MemorySpec.NONE, GuardrailSpec.OFF,
                LimitSpec.DEFAULT, OutputSpec.TEXT, Set.of(), "hash");
        DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice", "Alice",
                Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());
        DefaultAgentInvoker invoker = new DefaultAgentInvoker((s, p) -> model, null,
                () -> {
                    throw new AssertionError("no catalog needed");
                },
                id -> true, (a, p) -> true, (a, p, in, out) -> { }, t -> recorded.countDown(), e -> { }, registry,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build(),
                null);

        invoker.stream(agent, new AgentChatRequest(null, "hi", "r", UUID.randomUUID()), principal, null)
                .collectList().block();
        // the turn span is stopped in the stream's doFinally, which may still be running on the emitting thread
        // when block() returns; the turn is recorded right after the span stops
        assertThat(recorded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        List<String> ancestry = new ArrayList<>();
        for (Observation o = seenByModel.get(); o != null; o = o.getContext().getParentObservation() instanceof
                Observation parent ? parent : null) {
            ancestry.add(o.getContext().getContextualName() != null ? o.getContext().getContextualName()
                    : o.getContext().getName());
        }
        assertThat(ancestry).contains("dai.agent.turn");
        assertThat(stopped).contains("dai.agent.turn");
    }
}
