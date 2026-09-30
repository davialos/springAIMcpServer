package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelUnavailableException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ChatModel} that tries the agent's providers in order (primary, then each fallback) and skips providers
 * whose {@link ProviderBreaker} is open (OQ-47). Failover happens per model call, which has no side effects on our
 * side, so tools are never re-run: they execute outside the model, in the tool-calling loop. A streamed call fails
 * over only if nothing was emitted yet, so a client never sees two providers' text spliced together.
 *
 * <p>Each candidate is called with its own selection's model name, temperature and token limit on top of that
 * provider's own defaults; the tool callbacks and tool context of the request are carried over. Errors are logged by
 * class only (messages can quote prompts).
 */
@NullMarked
final class ResilientChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientChatModel.class);

    /**
     * One provider in the chain.
     *
     * @param selection the selection that resolved to this model
     * @param model     the provider's chat model
     * @param breaker   the provider's breaker
     */
    record Candidate(ModelSelection selection, ChatModel model, ProviderBreaker breaker) {}

    private final List<Candidate> chain;

    ResilientChatModel(List<Candidate> chain) {
        if (Objects.requireNonNull(chain, "chain").isEmpty()) {
            throw new IllegalArgumentException("chain must not be empty");
        }
        this.chain = List.copyOf(chain);
    }

    @Override
    public ChatOptions getOptions() {
        return chain.get(0).model().getOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            Candidate c = chain.get(i);
            if (!c.breaker().tryAcquire()) {
                continue;
            }
            try {
                ChatResponse response = c.model().call(forCandidate(prompt, c, i));
                c.breaker().onSuccess();
                return response;
            } catch (RuntimeException e) {
                c.breaker().onFailure();
                last = e;
                LOG.warn("Model provider {} failed ({}); {}", c.selection().providerId(), e.getClass().getSimpleName(),
                        i + 1 < chain.size() ? "trying the fallback" : "no fallback left");
                SafeMetrics.count("dynamic.ai.agent.model.failures");
            }
        }
        throw exhausted(last);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamFrom(prompt, 0, null);
    }

    private Flux<ChatResponse> streamFrom(Prompt prompt, int from, @Nullable RuntimeException previous) {
        for (int i = from; i < chain.size(); i++) {
            Candidate c = chain.get(i);
            if (!c.breaker().tryAcquire()) {
                continue;
            }
            int index = i;
            AtomicBoolean emitted = new AtomicBoolean(false);
            Flux<ChatResponse> source;
            try {
                source = c.model().stream(forCandidate(prompt, c, i));
            } catch (RuntimeException e) {
                c.breaker().onFailure();
                previous = e;
                continue;
            }
            return source.doOnNext(r -> emitted.set(true))
                    .doOnComplete(c.breaker()::onSuccess)
                    .onErrorResume(e -> {
                        c.breaker().onFailure();
                        RuntimeException failure = e instanceof RuntimeException re ? re : new IllegalStateException(e);
                        LOG.warn("Model provider {} stream failed ({}); {}", c.selection().providerId(),
                                e.getClass().getSimpleName(), emitted.get() ? "output already sent, not switching"
                                        : "trying the fallback");
                        SafeMetrics.count("dynamic.ai.agent.model.failures");
                        if (emitted.get()) {
                            return Flux.error(e);
                        }
                        return streamFrom(prompt, index + 1, failure);
                    });
        }
        return Flux.error(exhausted(previous));
    }

    private RuntimeException exhausted(@Nullable RuntimeException last) {
        if (last != null) {
            return last;
        }
        // every breaker refused: fail fast instead of piling calls onto struggling providers
        return new ModelUnavailableException(chain.get(0).selection().providerId());
    }

    /** The prompt for one candidate: the agent's options for that selection over the provider's own defaults. */
    static Prompt forCandidate(Prompt prompt, Candidate candidate, int index) {
        if (index == 0) {
            return prompt; // the invoker already applied the primary selection's options
        }
        ModelSelection selection = candidate.selection();
        ChatOptions.Builder<?> builder = candidate.model().getOptions().mutate().model(selection.modelName());
        if (selection.temperature() != null) {
            builder.temperature(selection.temperature());
        }
        if (selection.maxTokens() != null) {
            builder.maxTokens(selection.maxTokens());
        }
        if (builder instanceof ToolCallingChatOptions.Builder<?> tools
                && prompt.getOptions() instanceof ToolCallingChatOptions requested) {
            tools.toolCallbacks(requested.getToolCallbacks());
            tools.toolContext(requested.getToolContext());
        }
        return prompt.mutate().chatOptions(builder.build()).build();
    }
}
