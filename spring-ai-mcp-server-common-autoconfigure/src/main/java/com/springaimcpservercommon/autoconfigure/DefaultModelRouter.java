package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.model.ModelUnavailableException;
import com.springaimcpservercommon.ai.model.ResolvedModel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link ModelRouter}: picks the host's {@link ChatModel} bean for the agent's provider id, and falls back
 * to the selection's {@code fallback} when that provider has no model (F-49, basic form).
 *
 * <p>A provider id matches a bean when both, lower-cased with everything but letters and digits removed (and a
 * trailing {@code chatmodel} removed from the bean name), are equal: {@code openai} matches the bean
 * {@code openAiChatModel}, {@code azure-openai} matches {@code azureOpenAiChatModel}, {@code ollama} matches
 * {@code ollamaChatModel}. There is deliberately no "use whichever model exists" fallback: silently sending a
 * workspace's data to a different provider than configured would defeat data-residency rules (F-77).
 *
 * <p>{@link #resolveModel} wraps the chain of selections in a {@link ResilientChatModel}: a provider that errors
 * fails over to the next one, behind a per-provider {@link ProviderBreaker} (OQ-47). It also reports which selection matched, so the invoker applies that selection's model name,
 * temperature and token limit. Hosts can supply their own {@link ModelRouter} bean to change any of this.
 */
@NullMarked
final class DefaultModelRouter implements ModelRouter {

    private final Map<String, ChatModel> byProvider;
    private final java.util.concurrent.ConcurrentMap<String, ProviderBreaker> breakers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final int failureThreshold;
    private final java.time.Duration breakerOpenFor;
    private final java.time.Clock clock;

    DefaultModelRouter(Map<String, ChatModel> chatModelsByBeanName) {
        this(chatModelsByBeanName, 5, java.time.Duration.ofSeconds(30), java.time.Clock.systemUTC());
    }

    DefaultModelRouter(Map<String, ChatModel> chatModelsByBeanName, int failureThreshold,
                       java.time.Duration breakerOpenFor, java.time.Clock clock) {
        Objects.requireNonNull(chatModelsByBeanName, "chatModelsByBeanName");
        this.failureThreshold = failureThreshold;
        this.breakerOpenFor = Objects.requireNonNull(breakerOpenFor, "breakerOpenFor");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, ChatModel> index = new HashMap<>();
        chatModelsByBeanName.forEach((beanName, model) -> {
            String key = normalize(stripSuffix(beanName));
            ChatModel previous = index.putIfAbsent(key, model);
            if (previous != null && previous != model) {
                throw new IllegalStateException("two ChatModel beans map to provider '" + key + "'");
            }
        });
        this.byProvider = Map.copyOf(index);
    }

    /** The plain provider model, without failover or breaker (used by callers that want exactly one provider). */
    @Override
    public ChatModel resolve(ModelSelection selection, DaiPrincipal principal) {
        return match(selection).model();
    }

    /**
     * The model to call for a turn: the first selection with a provider bean, wrapped so that a failing provider
     * fails over to the following selections that have one, and an open breaker is skipped (OQ-47).
     */
    @Override
    public ResolvedModel resolveModel(ModelSelection selection, DaiPrincipal principal) {
        ResolvedModel first = match(selection);
        List<ResilientChatModel.Candidate> chain = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModelSelection s = first.selection(); s != null; s = s.fallback()) {
            String key = normalize(s.providerId());
            ChatModel model = byProvider.get(key);
            if (model != null && seen.add(key)) {
                chain.add(new ResilientChatModel.Candidate(s, model, breakers.computeIfAbsent(key,
                        k -> new ProviderBreaker(failureThreshold, breakerOpenFor, clock))));
            }
        }
        return new ResolvedModel(new ResilientChatModel(chain), first.selection());
    }

    private ResolvedModel match(ModelSelection selection) {
        ModelSelection current = selection;
        while (current != null) {
            ChatModel model = byProvider.get(normalize(current.providerId()));
            if (model != null) {
                return new ResolvedModel(model, current);
            }
            current = current.fallback();
        }
        throw new ModelUnavailableException(selection.providerId());
    }

    private static String stripSuffix(String beanName) {
        String lower = beanName.toLowerCase(Locale.ROOT);
        return lower.endsWith("chatmodel") ? beanName.substring(0, beanName.length() - "chatmodel".length())
                : beanName;
    }

    /** Lower-cases and keeps letters and digits only. */
    static String normalize(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (char c : id.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
