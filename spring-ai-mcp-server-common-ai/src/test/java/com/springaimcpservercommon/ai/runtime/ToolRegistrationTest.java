package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how Spring AI 2.0.1 combines what the invoker sets on a {@code ChatClient}: the tool loop must run, and the
 * agent's model name and temperature must reach the model without displacing the tools (the base options come from
 * {@code ChatModel.getOptions()}; a plain default-options builder is merged into them).
 */
class ToolRegistrationTest {

    private final AtomicInteger toolCalls = new AtomicInteger();
    private final List<Prompt> prompts = new ArrayList<>();

    private final ToolCallback tool = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("find_orders").description("d").inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            toolCalls.incrementAndGet();
            return "{\"rows\":[]}";
        }
    };

    /** Asks for the tool on the first call and answers on the second. */
    private final ChatModel model = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() % 2 == 1) {
                AssistantMessage ask = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id-1", "function", "find_orders", "{}"))).build();
                return new ChatResponse(List.of(new Generation(ask)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("provider-default").build();
        }
    };

    @Test
    void theToolLoopRunsAndTheAgentsModelOptionsAndToolsReachTheModel() {
        ChatClient client = ChatClient.builder(model)
                .defaultOptions(DefaultAgentInvoker.chatOptions(new ModelSelection("openai", "agent-model", 0.3, 512, null)))
                .build();

        String answer = client.prompt().user("orders?").toolCallbacks(List.of(tool)).call().content();

        assertThat(answer).isEqualTo("done");
        assertThat(toolCalls).hasValue(1);
        assertThat(prompts).hasSize(2).allSatisfy(p -> assertThat(p.getOptions())
                .isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
                    assertThat(options.getModel()).isEqualTo("agent-model");
                    assertThat(options.getTemperature()).isEqualTo(0.3);
                    assertThat(options.getMaxTokens()).isEqualTo(512);
                    assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                            .containsExactly("find_orders");
                }));
    }

    @Test
    void withoutAgentOptionsTheProvidersOwnDefaultsStayAndToolsStillRun() {
        ChatClient client = ChatClient.builder(model).build();

        client.prompt().user("orders?").toolCallbacks(List.of(tool)).call().content();

        assertThat(toolCalls).hasValue(1);
        assertThat(prompts.get(0).getOptions().getModel()).isEqualTo("provider-default");
    }
}
