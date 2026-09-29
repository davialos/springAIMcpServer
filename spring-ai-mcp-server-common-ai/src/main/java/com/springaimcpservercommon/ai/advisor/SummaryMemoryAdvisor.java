package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.MemorySpec;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * Implements the {@link MemorySpec.Strategy#SUMMARY} conversation memory strategy (LLD-06 §7).
 *
 * <p>On each turn this advisor:
 * <ol>
 *   <li><b>Before the model call</b> — retrieves the stored running summary for this conversation
 *       (keyed under {@code "dai:sum:<conversationId>"}) and prepends it to the system prompt as
 *       explicit context so the model is aware of earlier exchanges.</li>
 *   <li><b>After the model call</b> — passes the previous summary, the user message, and the
 *       assistant response to a summarization model call, producing a concise updated summary, then
 *       stores the result back into {@link ChatMemory} under the same key.</li>
 * </ol>
 *
 * <p>For <b>streaming turns</b> the summary is generated asynchronously in a virtual thread after
 * the stream completes, so it does not delay token delivery to the caller.
 *
 * <p>Summarization failures are logged at {@code WARN} level and swallowed — the advisor never
 * breaks the agent turn (LLD-12 §4: fail the feature, not the host). The previous summary is
 * retained on failure.
 *
 * <p>Not a Spring {@code @Component} — instantiated per turn in
 * {@link com.springaimcpservercommon.ai.runtime.DefaultAgentInvoker}.
 */
@NullMarked
public final class SummaryMemoryAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(SummaryMemoryAdvisor.class);

    /** {@link ChatMemory} key prefix for the stored summary (separate from message history). */
    static final String SUMMARY_KEY_PREFIX = "dai:sum:";

    /** System-prompt header injected before the summary text so the model knows the context origin. */
    private static final String SUMMARY_HEADER =
            "Conversation context (summary of earlier turns in this session):\n";

    private final ChatMemory chatMemory;
    private final ChatModel summarizationModel;
    private final int order;

    /**
     * Creates the advisor.
     *
     * @param chatMemory          conversation memory store (the same instance used by the agent)
     * @param summarizationModel  model used to compress conversation history into a running summary;
     *                            typically the same model as the main agent (cheap at 2–4 sentences)
     * @param order               advisor order; should match the memory advisor slot
     *                            ({@code Ordered.HIGHEST_PRECEDENCE + 201})
     */
    public SummaryMemoryAdvisor(ChatMemory chatMemory, ChatModel summarizationModel, int order) {
        this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory");
        this.summarizationModel = Objects.requireNonNull(summarizationModel, "summarizationModel");
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    // ── sync turn ─────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Injects the previous summary before the turn, then synchronously updates it after.
     * The summary generation runs inline (same thread) to guarantee it is committed before the
     * response is returned, so the next turn always has fresh context.
     */
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String convId = conversationId(request);
        String prevSummary = loadSummary(convId);

        AdvisedRequest augmented = injectSummary(request, prevSummary);
        AdvisedResponse response = chain.nextAroundCall(augmented);

        String userText = request.userText();
        String assistantText = extractText(response.response());
        // Run synchronously: ensures the summary is stored before the caller's next turn
        updateSummary(convId, prevSummary, userText, assistantText);

        return response;
    }

    // ── streaming turn ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Injects the previous summary before the stream starts. After the stream completes the
     * summary is updated asynchronously in a virtual thread to avoid blocking token delivery.
     */
    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        String convId = conversationId(request);
        String prevSummary = loadSummary(convId);

        AdvisedRequest augmented = injectSummary(request, prevSummary);
        String userText = request.userText();
        StringBuilder collectedResponse = new StringBuilder();

        return chain.nextAroundStream(augmented)
                .doOnNext(r -> {
                    String chunk = extractText(r.response());
                    if (!chunk.isEmpty()) collectedResponse.append(chunk);
                })
                .doOnComplete(() -> {
                    String assistantText = collectedResponse.toString();
                    // Off the reactor thread: use a virtual thread for the blocking model call
                    Thread.ofVirtual().start(() ->
                            updateSummary(convId, prevSummary, userText, assistantText));
                });
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** Loads the stored summary for this conversation, or empty string if none exists yet. */
    private String loadSummary(String convId) {
        if (convId.isBlank()) return "";
        List<org.springframework.ai.chat.messages.Message> messages =
                chatMemory.get(SUMMARY_KEY_PREFIX + convId, 1);
        if (messages.isEmpty()) return "";
        String text = messages.get(0).getText();
        return text != null ? text : "";
    }

    /** Returns a modified request with the summary prepended to the system prompt. */
    private static AdvisedRequest injectSummary(AdvisedRequest request, String summary) {
        if (summary.isBlank()) return request;
        String existing = request.systemText();
        String newSystem = (existing != null && !existing.isBlank()
                ? existing + "\n\n"
                : "")
                + SUMMARY_HEADER + summary;
        return request.mutate().systemText(newSystem).build();
    }

    /**
     * Generates an updated summary from the previous context + current exchange, then stores it.
     * If generation fails or produces blank output, the previous summary is left unchanged.
     */
    private void updateSummary(String convId, String prev, String user, String assistant) {
        if (convId.isBlank() || user.isBlank() || assistant.isBlank()) return;
        try {
            String newSummary = generateSummary(prev, user, assistant);
            if (newSummary != null && !newSummary.isBlank()) {
                chatMemory.clear(SUMMARY_KEY_PREFIX + convId);
                chatMemory.add(SUMMARY_KEY_PREFIX + convId,
                        List.of(new AssistantMessage(newSummary)));
            }
        } catch (Exception e) {
            LOG.warn("Conversation summary update failed for conversation {}; retaining previous summary",
                    convId, e);
        }
    }

    /**
     * Calls the summarization model to produce a 2–4 sentence plain-text summary.
     *
     * @return the summary text, or {@code null} if the model call fails
     */
    private @Nullable String generateSummary(String prev, String user, String assistant) {
        String prompt = buildSummaryPrompt(prev, user, assistant);
        try {
            ChatResponse response = summarizationModel.call(new Prompt(prompt));
            if (response != null && response.getResult() != null) {
                String text = response.getResult().getOutput().getText();
                return text != null ? text.strip() : null;
            }
        } catch (Exception e) {
            LOG.warn("Summarization model call failed", e);
        }
        return null;
    }

    /**
     * Builds the summarization prompt. Output must be plain text — no markdown, no JSON —
     * so it can be safely injected into any system prompt without formatting side-effects.
     */
    private static String buildSummaryPrompt(String prev, String user, String assistant) {
        var sb = new StringBuilder(512);
        sb.append("You are a conversation summarizer. ");
        sb.append("Produce a brief factual summary (2 to 4 sentences, plain text, no markdown). ");
        sb.append("Capture the key topics discussed and any important conclusions or facts established.\n\n");
        if (!prev.isBlank()) {
            sb.append("PREVIOUS SUMMARY:\n").append(prev).append("\n\n");
            sb.append("LATEST EXCHANGE:\n");
        } else {
            sb.append("CONVERSATION SO FAR:\n");
        }
        sb.append("User: ").append(user).append("\n");
        sb.append("Assistant: ").append(assistant).append("\n\n");
        sb.append("Write the updated summary:\n");
        return sb.toString();
    }

    private static String extractText(@Nullable ChatResponse response) {
        if (response == null || response.getResult() == null) return "";
        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    private static String conversationId(AdvisedRequest request) {
        Object id = request.adviseContext().get(ChatMemory.CONVERSATION_ID);
        return id instanceof String s ? s : "";
    }
}
