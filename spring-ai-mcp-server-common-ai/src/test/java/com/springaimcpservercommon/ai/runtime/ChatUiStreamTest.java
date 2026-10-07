package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.ChatUiSpec;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.agent.ToolBindingRef;
import com.springaimcpservercommon.ai.chat.ChatUiRuntime;
import com.springaimcpservercommon.ai.chat.ChatUiState;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentChatRequest;
import com.springaimcpservercommon.ai.safety.TurnSafety;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.guard.CompositePromptValidator;
import com.springaimcpservercommon.core.guard.PiiRedactor;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Step details, tool events and choice components on streamed and synchronous turns (LLD-13 §3). */
class ChatUiStreamTest {

    private static final String CHOICE_ARGS = "{\"question\":\"Which order, ann@acme.io?\",\"options\":["
            + "{\"value\":\"PO-1\",\"label\":\"PO-1\"},{\"value\":\"PO-2\",\"label\":\"PO-2\"}]}";

    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());
    private final List<ChatUiState.ShownComponent> shown = new CopyOnWriteArrayList<>();
    private final AtomicInteger turns = new AtomicInteger();

    private final ChatUiState state = new ChatUiState() {
        @Override
        public void componentShown(ShownComponent component) {
            shown.add(component);
        }

        @Override
        public Optional<StoredComponent> find(String conversationKey, UUID turnId, String componentId) {
            return Optional.empty();
        }

        @Override
        public boolean answer(String conversationKey, UUID turnId, String componentId, String answerJson) {
            return true;
        }

        @Override
        public void feedback(Feedback feedback) {
        }

        @Override
        public Snapshot load(String conversationKey) {
            return new Snapshot(List.of(), List.of());
        }
    };

    private final ToolCallback findOrders = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("find_orders").description("Finds orders")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            return "{\"tool\":\"find_orders\",\"status\":\"OK\",\"entity\":\"Order\",\"count\":2,"
                    + "\"data\":[{\"id\":\"PO-1\",\"contact\":\"ann@acme.io\"}],\"truncated\":false}";
        }
    };

    private AgentDefinition agent(ChatUiSpec ui) {
        return new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "shop-bot", "Shop", "You help.",
                new ModelSelection("openai", "m", null, null, null), List.of(new ToolBindingRef(UUID.randomUUID(), 1)),
                MemorySpec.NONE, GuardrailSpec.OFF, LimitSpec.DEFAULT, new OutputSpec(OutputSpec.Mode.TEXT, null, null, ui),
                Set.of(), "hash");
    }

    /** Round 1 calls find_orders, round 2 calls present_choices, round 3 answers. */
    private ChatModel model() {
        AtomicInteger round = new AtomicInteger();
        return new ChatModel() {
            private ChatResponse next() {
                return switch (round.incrementAndGet()) {
                    case 1 -> toolCall("find_orders", "{\"customer\":\"ann@acme.io\"}");
                    case 2 -> toolCall("present_choices", CHOICE_ARGS);
                    default -> new ChatResponse(List.of(new Generation(new AssistantMessage("Please pick one."))));
                };
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                return next();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(next());
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }

    private static ChatResponse toolCall(String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id-" + name, "function", name, args))).build())));
    }

    private DefaultAgentInvoker invoker(ChatUiRuntime chatUi) {
        ToolBridge bridge = mock(ToolBridge.class);
        when(bridge.buildCallbacks(any(), any(), any(), any(), any())).thenReturn(List.of(findOrders));
        EffectiveCatalog catalog = new EffectiveCatalog(1, "s", "p", Map.of(), Map.of(), List.of(), List.of());
        TurnSafety safety = new TurnSafety(CompositePromptValidator.defaults(), PiiRedactor.defaults(), () -> catalog,
                TurnSafety.Settings.OFF);
        ChatModel model = model();
        return new DefaultAgentInvoker((selection, p) -> model, bridge, () -> catalog, id -> true, (a, p) -> true,
                (a, p, in, out) -> { }, t -> turns.incrementAndGet(), e -> { }, ObservationRegistry.NOOP,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build(),
                null, safety, null, 0, chatUi);
    }

    private List<StreamEvent> stream(DefaultAgentInvoker invoker, AgentDefinition agent) {
        List<StreamEvent> events = invoker.stream(agent, new AgentChatRequest(null, "show my orders", "r",
                UUID.randomUUID()), principal, null).collectList().block();
        Awaits.until(() -> turns.get() > 0);
        return events;
    }

    @Test
    void aStreamedTurnCarriesUiFlagsStepsToolEventsAndTheChoiceInOrder() {
        AgentDefinition agent = agent(new ChatUiSpec(true, true, true, true));

        List<StreamEvent> events = stream(invoker(new ChatUiRuntime(null, state)), agent);

        assertThat(events).extracting(StreamEvent::type).containsExactly("turn.start", "step", "tool.call",
                "tool.result", "ui.component", "text.delta", "usage", "turn.end");
        assertThat(events.getFirst().toJson())
                .contains("\"ui\":{\"choices\":true,\"copy\":true,\"feedback\":true,\"steps\":true}");
        assertThat(events.get(2)).isInstanceOfSatisfying(StreamEvent.ToolCall.class, call -> {
            assertThat(call.tool()).isEqualTo("find_orders");
            assertThat(call.argsPreview()).isEqualTo("{\"customer\":\"[redacted email]\"}");
        });
        assertThat(events.get(3)).isInstanceOfSatisfying(StreamEvent.ToolResult.class, r -> {
            assertThat(r.status()).isEqualTo("ok");
            assertThat(r.summary()).isEqualTo("2 Orders returned.");
        });
        assertThat(events.get(4)).isInstanceOfSatisfying(StreamEvent.UiComponent.class, c -> {
            assertThat(c.componentType()).isEqualTo("choice");
            assertThat(c.componentId()).isEqualTo("choice-1");
            assertThat(c.payload()).contains("Which order, [redacted email]?").doesNotContain("ann@acme.io");
        });
        assertThat(shown).singleElement().satisfies(s -> {
            assertThat(s.agentId()).isEqualTo(agent.id());
            assertThat(s.principalId()).isEqualTo(principal.principalId());
            assertThat(s.conversationKey()).startsWith("sha256:");
        });
    }

    @Test
    void hostDefaultsApplyWhenTheAgentSaysNothingAndChoicesNeedOptingIn() {
        AgentDefinition agent = new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "plain-bot", "Plain",
                "You help.", new ModelSelection("openai", "m", null, null, null), List.of(), MemorySpec.NONE,
                GuardrailSpec.OFF, LimitSpec.DEFAULT, OutputSpec.TEXT, Set.of(), "hash");
        ChatModel text = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("hi")))));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        EffectiveCatalog catalog = new EffectiveCatalog(1, "s", "p", Map.of(), Map.of(), List.of(), List.of());
        DefaultAgentInvoker invoker = new DefaultAgentInvoker((s, p) -> text, null, () -> catalog, id -> true,
                (a, p) -> true, (a, p, in, out) -> { }, t -> turns.incrementAndGet(), e -> { }, ObservationRegistry.NOOP,
                MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build(),
                null, TurnSafety.disabled(), null, 0, new ChatUiRuntime(ChatUiSpec.DEFAULT, state));

        List<StreamEvent> events = stream(invoker, agent);

        assertThat(events).extracting(StreamEvent::type).containsExactly("turn.start", "step", "text.delta", "usage",
                "turn.end");
        assertThat(events.getFirst().toJson()).contains("\"choices\":false");
        assertThat(shown).isEmpty();
    }

    @Test
    void withTheChatUiOffTheStreamIsUnchanged() {
        List<StreamEvent> events = stream(invoker(ChatUiRuntime.OFF), agent(null));

        assertThat(events).extracting(StreamEvent::type).doesNotContain("step", "tool.call", "tool.result",
                "ui.component");
        assertThat(events.getFirst().toJson()).doesNotContain("\"ui\"");
    }

    @Test
    void aSynchronousTurnReturnsToolCallsAndComponents() {
        var result = invoker(new ChatUiRuntime(null, state)).invoke(agent(new ChatUiSpec(true, true, true, true)),
                new AgentChatRequest(null, "show my orders", "r"), principal, null);

        assertThat(result.message()).isEqualTo("Please pick one.");
        assertThat(result.toolCalls()).extracting(AgentInvoker.ToolCallRecord::tool).containsExactly("find_orders");
        assertThat(result.components()).singleElement()
                .satisfies(c -> assertThat(c.componentId()).isEqualTo("choice-1"));
    }
}
