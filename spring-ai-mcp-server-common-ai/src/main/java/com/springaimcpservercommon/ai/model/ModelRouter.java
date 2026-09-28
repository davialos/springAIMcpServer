package com.springaimcpservercommon.ai.model;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Port: resolves the {@link ChatModel} to use for an agent invocation (LLD-06 §5).
 *
 * <p>The autoconfigure module provides the default implementation, which:
 * <ul>
 *   <li>Looks up the registered {@link ChatModel} bean for {@code selection.providerId()}.</li>
 *   <li>Falls back to {@code selection.fallback()} when the primary provider is unavailable.</li>
 *   <li>Applies {@code selection.temperature()} and {@code selection.maxTokens()} via request options.</li>
 * </ul>
 *
 * <p>Host applications can replace this by declaring a {@code @Bean ModelRouter} — the
 * autoconfigure default is {@code @ConditionalOnMissingBean}.
 */
@NullMarked
public interface ModelRouter {

    /**
     * Resolves the chat model for the given agent model selection and calling principal.
     *
     * <p>The principal is provided so a multi-tenant host can route requests to tenant-specific
     * model endpoints or apply usage quotas per principal.
     *
     * @param selection the model selection from the {@link com.springaimcpservercommon.ai.agent.AgentDefinition}
     * @param principal the calling principal
     * @return the chat model to use for this turn; never {@code null}
     * @throws ModelUnavailableException if no suitable model can be found (including fallback)
     */
    ChatModel resolve(ModelSelection selection, DaiPrincipal principal);
}
