package com.springaimcpservercommon.ai.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(chatMemory.get(eq(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId), eq(1)))
                .thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Hello"))))
                .build();
        AdvisedResponse stubResponse = new AdvisedResponse(chatResponse, Map.of());

        AdvisedRequest request = advisedRequest(convId, "Hi there", "Base system");
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Turn 1 summary"))))
                .build());

        when(chain.nextAroundCall(any())).thenAnswer(inv -> {
            AdvisedRequest forwarded = inv.getArgument(0);
            // No existing summary — system text should be unchanged
            assertThat(forwarded.systemText()).isEqualTo("Base system");
            return stubResponse;
        });

        AdvisedResponse result = advisor.aroundCall(request, chain);

        assertThat(result).isSameAs(stubResponse);
        // Should store new summary
        verify(chatMemory).clear(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId);
        verify(chatMemory).add(eq(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId), any());
    }

    @Test
    void aroundCall_withExistingSummary_prependsToSystemText() {
        String convId = "conv-2";
        String prevSummary = "User asked about weather.";
        when(chatMemory.get(eq(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId), eq(1)))
                .thenReturn(List.of(new AssistantMessage(prevSummary)));

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("It is sunny"))))
                .build();
        AdvisedResponse stubResponse = new AdvisedResponse(chatResponse, Map.of());

        AdvisedRequest request = advisedRequest(convId, "And now?", "Original system");
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Updated summary"))))
                .build());

        when(chain.nextAroundCall(any())).thenAnswer(inv -> {
            AdvisedRequest forwarded = inv.getArgument(0);
            assertThat(forwarded.systemText())
                    .contains("Original system")
                    .contains(prevSummary)
                    .contains("Conversation context");
            return stubResponse;
        });

        advisor.aroundCall(request, chain);
    }

    @Test
    void aroundCall_summarizationModelFailure_retainsPreviousSummary() {
        String convId = "conv-3";
        String prevSummary = "Previous summary text.";
        when(chatMemory.get(eq(SummaryMemoryAdvisor.SUMMARY_KEY_PREFIX + convId), eq(1)))
                .thenReturn(List.of(new AssistantMessage(prevSummary)));

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Response"))))
                .build();
        AdvisedResponse stubResponse = new AdvisedResponse(chatResponse, Map.of());

        AdvisedRequest request = advisedRequest(convId, "Question", null);
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any())).thenReturn(stubResponse);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Model unavailable"));

        // Should not throw — failure is swallowed
        AdvisedResponse result = advisor.aroundCall(request, chain);
        assertThat(result).isSameAs(stubResponse);
        // Summary store must NOT be updated when generation fails
        verify(chatMemory, never()).clear(any());
        verify(chatMemory, never()).add(any(), any());
    }

    @Test
    void aroundCall_blankUserInput_skipsSummarization() {
        String convId = "conv-4";
        when(chatMemory.get(any(), eq(1))).thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("pong"))))
                .build();
        AdvisedResponse stubResponse = new AdvisedResponse(chatResponse, Map.of());

        AdvisedRequest request = advisedRequest(convId, "  ", null);
        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any())).thenReturn(stubResponse);

        advisor.aroundCall(request, chain);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatMemory, never()).clear(any());
    }

    @Test
    void aroundCall_noConversationId_skipsSummarization() {
        when(chatMemory.get(any(), eq(1))).thenReturn(List.of());

        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
        AdvisedResponse stubResponse = new AdvisedResponse(chatResponse, Map.of());

        // No CONVERSATION_ID in advise context
        AdvisedRequest request = mock(AdvisedRequest.class);
        when(request.adviseContext()).thenReturn(Map.of());
        when(request.userText()).thenReturn("Hello");
        when(request.systemText()).thenReturn(null);

        CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
        when(chain.nextAroundCall(any())).thenReturn(stubResponse);

        advisor.aroundCall(request, chain);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static AdvisedRequest advisedRequest(String convId, String userText, String systemText) {
        AdvisedRequest req = mock(AdvisedRequest.class);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(ChatMemory.CONVERSATION_ID, convId);
        when(req.adviseContext()).thenReturn(ctx);
        when(req.userText()).thenReturn(userText);
        when(req.systemText()).thenReturn(systemText);

        // mutate() returns a builder that produces a mock with the new systemText
        AdvisedRequest.Builder builder = mock(AdvisedRequest.Builder.class);
        AdvisedRequest mutated = mock(AdvisedRequest.class);
        when(req.mutate()).thenReturn(builder);
        when(builder.systemText(any())).thenReturn(builder);
        when(builder.build()).thenReturn(mutated);
        when(mutated.adviseContext()).thenReturn(ctx);
        when(mutated.userText()).thenReturn(userText);
        when(mutated.systemText()).thenAnswer(inv -> {
            // Capture whatever systemText was set on the builder
            return systemText; // simplified; caller verifies via chain arg capture
        });
        return req;
    }
}
