package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.mcp.McpScopeDecision;
import com.springaimcpservercommon.security.mcp.McpScopeEvaluator;
import com.springaimcpservercommon.security.mcp.McpToolRequirement;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * SPI: computes the list of {@link ToolCallback} instances visible to an MCP caller (LLD-07 §5.2).
 *
 * <p>The effective tool list = published tool bindings with {@code mcpExposed=true}
 * ∩ caller's grants ∩ token scopes. Evaluated per request (STATELESS) or per session
 * (STATEFUL, re-evaluated on catalog snapshot change).
 *
 * <p>The autoconfigure module provides a default implementation ({@code DefaultMcpToolsProvider})
 * using {@code @ConditionalOnMissingBean}.
 */
@NullMarked
public interface McpToolsProvider {

    /**
     * Returns the tool callbacks this caller may see and invoke on the MCP endpoint.
     *
     * @param principal      calling principal (from the OAuth token or API key)
     * @param authentication Spring Security authentication object
     * @param catalog        effective catalog snapshot for this workspace
     * @param workspaceId    workspace being served
     * @param scopeEvaluator MCP scope evaluator for per-tool access checks
     * @return immutable list of accessible, secured tool callbacks
     */
    List<ToolCallback> toolsForRequest(DaiPrincipal principal,
                                        Authentication authentication,
                                        EffectiveCatalog catalog,
                                        UUID workspaceId,
                                        McpScopeEvaluator scopeEvaluator);
}
