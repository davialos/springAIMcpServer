package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a {@link PromptValidator} gets to judge one user prompt.
 *
 * @param prompt         the user's message, as typed
 * @param workspaceId    workspace of the agent
 * @param agentSlug      the agent being invoked
 * @param principal      the caller
 * @param policy         effective validation policy (agent policy combined with the host floor)
 * @param topicAllowList the agent's topic allow-list (also counts as in-scope vocabulary)
 * @param catalog        the current effective catalog; read lazily, only by validators that need it
 */
public record PromptValidationRequest(String prompt, UUID workspaceId, String agentSlug, DaiPrincipal principal,
                                      InputValidationPolicy policy, List<String> topicAllowList,
                                      Supplier<EffectiveCatalog> catalog) {

    /** Validates components. */
    public PromptValidationRequest {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(agentSlug, "agentSlug");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(policy, "policy");
        topicAllowList = List.copyOf(topicAllowList);
        Objects.requireNonNull(catalog, "catalog");
    }
}
