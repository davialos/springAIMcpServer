package com.springaimcpservercommon.ai.agent;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable, published agent definition (LLD-06 §2).
 *
 * <p>Agents are authored in the admin dashboard, reviewed and published through the config lifecycle
 * (LLD-09). The runtime assembles a {@code ChatClient} per revision and caches it.
 *
 * @param id            unique agent id (UUIDv7)
 * @param revision      monotonically increasing revision
 * @param workspaceId   owning workspace
 * @param slug          URL-safe slug for the chat API ({@code ^[a-z][a-z0-9-]{2,63}$})
 * @param displayName   human-readable name shown in the dashboard and review UI
 * @param systemPrompt  system prompt template; may reference {@code {catalogSummary}}
 * @param model         model selection and parameters
 * @param tools         references to tool bindings allowed for this agent
 * @param memory        conversation memory configuration
 * @param guardrails    input/output guardrail settings
 * @param limits        per-turn and per-conversation limits
 * @param output        output format (text or JSON schema)
 * @param references    all catalog elements this agent references (for drift detection)
 * @param catalogHash   effective catalog fingerprint validated against at publish time
 */
public record AgentDefinition(
        UUID id,
        int revision,
        UUID workspaceId,
        String slug,
        String displayName,
        String systemPrompt,
        ModelSelection model,
        List<ToolBindingRef> tools,
        MemorySpec memory,
        GuardrailSpec guardrails,
        LimitSpec limits,
        OutputSpec output,
        Set<CatalogElementRef> references,
        String catalogHash) {

    private static final java.util.regex.Pattern SLUG_PATTERN =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    /** Validates components and defensively copies collections. */
    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(slug, "slug");
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match ^[a-z][a-z0-9-]{2,63}$: " + slug);
        }
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(guardrails, "guardrails");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(catalogHash, "catalogHash");
        tools = List.copyOf(tools);
        references = Set.copyOf(references);
    }
}
