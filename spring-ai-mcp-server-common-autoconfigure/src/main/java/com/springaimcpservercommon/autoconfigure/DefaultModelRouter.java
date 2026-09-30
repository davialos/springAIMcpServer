package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.model.ModelUnavailableException;
import com.springaimcpservercommon.ai.model.ResolvedModel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ChatModel;

import java.util.HashMap;
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
 * <p>{@link #resolveModel} also reports which selection matched, so the invoker applies that selection's model name,
 * temperature and token limit. Known limit (OQ-47): there is no circuit breaker or failover on provider errors,
 * only on a missing provider. Hosts can supply their own {@link ModelRouter} bean to change any of this.
 */
@NullMarked
final class DefaultModelRouter implements ModelRouter {

    private final Map<String, ChatModel> byProvider;

    DefaultModelRouter(Map<String, ChatModel> chatModelsByBeanName) {
        Objects.requireNonNull(chatModelsByBeanName, "chatModelsByBeanName");
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

    @Override
    public ChatModel resolve(ModelSelection selection, DaiPrincipal principal) {
        return resolveModel(selection, principal).model();
    }

    @Override
    public ResolvedModel resolveModel(ModelSelection selection, DaiPrincipal principal) {
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
