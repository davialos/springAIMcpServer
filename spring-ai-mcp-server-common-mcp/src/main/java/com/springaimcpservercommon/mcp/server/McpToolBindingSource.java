package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.ai.tool.ToolBinding;

import java.util.List;
import java.util.UUID;

/**
 * Port: loads the tool bindings that have {@code mcpExposed=true} for a workspace.
 *
 * <p>Implemented in the {@code persistence} module and injected by {@code autoconfigure}.
 */
@FunctionalInterface
public interface McpToolBindingSource {

    /**
     * Returns all published, MCP-exposed tool bindings for a workspace.
     *
     * @param workspaceId the workspace
     * @return immutable list; empty if none or if MCP is disabled for this workspace
     */
    List<ToolBinding> mcpExposedBindings(UUID workspaceId);
}
