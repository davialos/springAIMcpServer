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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Streamed turns of JSON_SCHEMA agents are held back, validated when the stream ends, then sent whole or refused. */
class StructuredStreamTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"name\"]}";

    private final List<ConversationRecorder.Exchange> exchanges = new CopyOnWriteArrayList<>();
    private final List<TurnRecorder.TurnRecord> turns = new CopyOnWriteArrayList<>();

    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private AgentDefinition agent(OutputSpec output) {
        return new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "extractor", "Extractor", "Extract.",
                new ModelSelection("openai", "m", null, null, null), List.of(), MemorySpec.NONE, GuardrailSpec.OFF,
                LimitSpec.DEFAULT, output, Set.of(), "hash");
    }

    private ChatModel streaming(String... chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromArray(chunks).map(c -> new ChatResponse(List.of(new Generation(new AssistantMessage(c)))));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }

    private List<StreamEvent> run(AgentDefinition agent, ChatModel model) {
        DefaultAgentInvoker invoker = new DefaultAgentInvoker((selection, p) -> model, null,
                () -> {
                    throw new AssertionError("no catalog needed");
                },
                id -> true, (a, p) -> true, (a, p, in, out) -> { }, turns::add, exchanges::add,
                ObservationRegistry.NOOP,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build(),
                (schema, json) -> json.contains("\"name\"") ? List.of() : List.of("$.name is required"));
        return invoker.stream(agent, new AgentChatRequest(null, "extract", "r", UUID.randomUUID()), principal, null)
                .collectList().block();
    }

    private static String text(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        events.forEach(e -> {
            if (e instanceof StreamEvent.TextDelta d) {
                sb.append(d.text());
            }
        });
        return sb.toString();
    }

    @Test
    void aValidDocumentIsSentWholeAfterTheStreamEnds() {
        var events = run(agent(new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, SCHEMA)),
                streaming("{\"na", "me\":", "\"Ann\"}"));

        assertThat(events.stream().filter(e -> e instanceof StreamEvent.TextDelta)).hasSize(1);
        assertThat(text(events)).isEqualTo("{\"name\":\"Ann\"}");
        assertThat(events).noneMatch(e -> e instanceof StreamEvent.ErrorEvent);
        assertThat(turns).singleElement().satisfies(t -> assertThat(t.outcome())
                .isEqualTo(TurnRecorder.Outcome.SUCCESS));
    }

    @Test
    void aDocumentThatFailsTheSchemaIsRefusedAndNeverReachesTheClient() {
        var events = run(agent(new OutputSpec(OutputSpec.Mode.JSON_SCHEMA, SCHEMA)),
                streaming("{\"age\":", "3}"));

        assertThat(text(events)).isEmpty();
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                StreamEvent.ErrorEvent.class, err -> {
                    assertThat(err.code()).isEqualTo("output-schema-violation");
                    assertThat(err.retryable()).isFalse();
                }));
        assertThat(turns).singleElement().satisfies(t -> assertThat(t.outcome())
                .isEqualTo(TurnRecorder.Outcome.FAILED));
        assertThat(exchanges).isEmpty();
    }

    @Test
    void textAgentsStillStreamChunkByChunk() {
        var events = run(agent(OutputSpec.TEXT), streaming("Hel", "lo"));

        assertThat(events.stream().filter(e -> e instanceof StreamEvent.TextDelta)).hasSize(2);
        assertThat(text(events)).isEqualTo("Hello");
    }
}
