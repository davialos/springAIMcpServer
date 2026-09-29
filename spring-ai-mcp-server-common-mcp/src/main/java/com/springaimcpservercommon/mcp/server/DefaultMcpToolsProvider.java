package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.ai.tool.ToolCallScope;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.security.mcp.McpScopeDecision;
import com.springaimcpservercommon.security.mcp.McpScopeEvaluator;
import com.springaimcpservercommon.security.mcp.McpToolRequirement;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/**
 * Default implementation of {@link McpToolsProvider} (LLD-07 §5.2).
 *
 * <p>For each {@code mcpExposed=true} binding in the workspace:
 * <ol>
 *   <li>Determines the tool kind (READ for read-only sources, WRITE for operations with proposals,
 *       AGENT for agent-backed tools).</li>
 *   <li>Evaluates the MCP scope + authorization engine via {@link McpScopeEvaluator}.</li>
 *   <li>Wraps the resolved delegate in a {@link SecuredToolCallback}.</li>
 * </ol>
 *
 * <p>Bindings that fail scope or authorization are silently excluded from the tool list
 * (default-deny: the caller cannot determine whether hidden tools exist).
 */
@NullMarked
public final class DefaultMcpToolsProvider implements McpToolsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMcpToolsProvider.class);

    private final McpToolBindingSource bindingSource;
    private final ToolBridge toolBridge;

    /**
     * Creates the provider.
     *
     * @param bindingSource loads the workspace's MCP-exposed bindings
     * @param toolBridge    resolves and secures callbacks from catalog + source
     */
    public DefaultMcpToolsProvider(McpToolBindingSource bindingSource, ToolBridge toolBridge) {
        this.bindingSource = Objects.requireNonNull(bindingSource, "bindingSource");
        this.toolBridge = Objects.requireNonNull(toolBridge, "toolBridge");
    }

    @Override
    public List<ToolCallback> toolsForRequest(DaiPrincipal principal,
                                               Authentication authentication,
                                               EffectiveCatalog catalog,
                                               UUID workspaceId,
                                               McpScopeEvaluator scopeEvaluator,
                                               @org.jspecify.annotations.Nullable ToolCallScope scope) {
        List<ToolBinding> bindings = bindingSource.mcpExposedBindings(workspaceId);
        List<ToolCallback> result = new ArrayList<>(bindings.size());

        for (ToolBinding binding : bindings) {
            McpToolRequirement.Kind kind = toolKind(binding);
            ResourceRef resource = ResourceRef.of(workspaceId, binding.id(), Classification.PUBLIC);
            McpToolRequirement requirement = new McpToolRequirement(kind, binding.toolName(), resource);

            McpScopeDecision decision = scopeEvaluator.evaluate(principal, requirement);
            if (!(decision instanceof McpScopeDecision.Allowed)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("MCP: tool {} excluded for principal {} (scope/authz denied)",
                            binding.toolName(), principal.principalId());
                }
                continue;
            }

            ToolCallback callback = toolBridge.buildCallback(binding, principal, authentication, catalog, scope);
            if (callback == null) {
                LOG.warn("MCP: cannot resolve delegate for tool {}; skipping", binding.toolName());
                continue;
            }
            result.add(callback);
        }

        return List.copyOf(result);
    }

    private static McpToolRequirement.Kind toolKind(ToolBinding binding) {
        return switch (binding.source()) {
            case ToolSource.AgentSource ignored -> McpToolRequirement.Kind.AGENT;
            default -> binding.writeMode() == com.springaimcpservercommon.ai.tool.WriteMode.PROPOSE
                    ? McpToolRequirement.Kind.WRITE
                    : McpToolRequirement.Kind.READ;
        };
    }
}
