package com.springaimcpservercommon.ai.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryMemoryAdvisorTest {

    private ChatMemory chatMemory;
    private ChatModel chatModel;
    private SummaryMemoryAdvisor advisor;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        chatModel = mock(ChatModel.class);
        advisor = new SummaryMemoryAdvisor(chatMemory, chatModel, 201);
    }

    @Test
    void getOrder_returnsSuppliedOrder() {
        assertThat(advisor.getOrder()).isEqualTo(201);
    }

    @Test
    void aroundCall_noExistingSummary_doesNotInjectSystemText() {
        String convId = "conv-1";
        when(chatMemory.get(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId))
                .thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Hello"))))
                .build();
        ChatClientResponse stubResponse = new ChatClientResponse(chatResponse, Map.of());

        ChatClientRequest request = advisedRequest(convId, "Hi there", "Base system");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Turn 1 summary"))))
                .build());

        when(chain.nextCall(any())).thenAnswer(inv -> {
            ChatClientRequest forwarded = inv.getArgument(0);
            // No existing summary — system text should be unchanged
            assertThat(systemText(forwarded)).isEqualTo("Base system");
            return stubResponse;
        });

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(stubResponse);
        // Should store new summary
        verify(chatMemory).clear(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId);
        verify(chatMemory).add(eq(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId), anyList());
    }

    @Test
    void aroundCall_withExistingSummary_prependsToSystemText() {
        String convId = "conv-2";
        String prevSummary = "User asked about weather.";
        when(chatMemory.get(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId))
                .thenReturn(List.of(new AssistantMessage(prevSummary)));

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("It is sunny"))))
                .build();
        ChatClientResponse stubResponse = new ChatClientResponse(chatResponse, Map.of());

        ChatClientRequest request = advisedRequest(convId, "And now?", "Original system");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Updated summary"))))
                .build());

        when(chain.nextCall(any())).thenAnswer(inv -> {
            ChatClientRequest forwarded = inv.getArgument(0);
            assertThat(systemText(forwarded))
                    .contains("Original system")
                    .contains(prevSummary)
                    .contains("Conversation context");
            return stubResponse;
        });

        advisor.adviseCall(request, chain);
    }

    @Test
    void aroundCall_summarizationModelFailure_retainsPreviousSummary() {
        String convId = "conv-3";
        String prevSummary = "Previous summary text.";
        when(chatMemory.get(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId))
                .thenReturn(List.of(new AssistantMessage(prevSummary)));

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Response"))))
                .build();
        ChatClientResponse stubResponse = new ChatClientResponse(chatResponse, Map.of());

        ChatClientRequest request = advisedRequest(convId, "Question", null);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(stubResponse);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Model unavailable"));

        // Should not throw — failure is swallowed
        ChatClientResponse result = advisor.adviseCall(request, chain);
        assertThat(result).isSameAs(stubResponse);
        // Summary store must NOT be updated when generation fails
        verify(chatMemory, never()).clear(any());
        verify(chatMemory, never()).add(any(String.class), anyList());
    }

    @Test
    void aroundCall_blankUserInput_skipsSummarization() {
        String convId = "conv-4";
        when(chatMemory.get(any())).thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("pong"))))
                .build();
        ChatClientResponse stubResponse = new ChatClientResponse(chatResponse, Map.of());

        ChatClientRequest request = advisedRequest(convId, "  ", null);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(stubResponse);

        advisor.adviseCall(request, chain);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatMemory, never()).clear(any());
    }

    @Test
    void aroundCall_noConversationId_skipsSummarization() {
        when(chatMemory.get(any())).thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
        ChatClientResponse stubResponse = new ChatClientResponse(chatResponse, Map.of());

        // No CONVERSATION_ID in advise context
        ChatClientRequest request = new ChatClientRequest(new Prompt("Hello"), Map.of());

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(stubResponse);

        advisor.adviseCall(request, chain);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ChatClientRequest advisedRequest(String convId, String userText, String systemText) {
        List<Message> messages = new java.util.ArrayList<>();
        if (systemText != null) {
            messages.add(new SystemMessage(systemText));
        }
        messages.add(new org.springframework.ai.chat.messages.UserMessage(userText));
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(ChatMemory.CONVERSATION_ID, convId);
        return new ChatClientRequest(new Prompt(messages), ctx);
    }

    private static String systemText(ChatClientRequest request) {
        return request.prompt().getSystemMessage().getText();
    }
}
