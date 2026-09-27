package com.springaimcpservercommon.security.mcp;

import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.security.permission.McpScope;
import com.springaimcpservercommon.security.permission.Permission;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one MCP tool call requires (LLD-07 §5.4).
 *
 * @param kind     tool kind (decides the scope)
 * @param toolName MCP tool name ({@code ^[a-z][a-z0-9_]{2,63}$})
 * @param resource the tool binding or agent resource invoked
 */
public record McpToolRequirement(Kind kind, String toolName, ResourceRef resource) {

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    /** Tool kinds. */
    public enum Kind {
        /** {@code readOnly=true} tool binding: scope {@code dai.mcp.read}, permission {@code tool:invoke}. */
        READ(McpScope.READ, List.of(Permission.TOOL_INVOKE)),
        /** Write tool (creates proposals only): scope {@code dai.mcp.propose}, {@code tool:invoke} + {@code data:write-propose}. */
        WRITE(McpScope.PROPOSE, List.of(Permission.TOOL_INVOKE, Permission.DATA_WRITE_PROPOSE)),
        /** {@code ask_<agent>} tool: scope {@code dai.mcp.agents}, permission {@code agent:invoke}. */
        AGENT(McpScope.AGENTS, List.of(Permission.AGENT_INVOKE));

        private final McpScope scope;
        private final List<Permission> permissions;

        Kind(McpScope scope, List<Permission> permissions) {
            this.scope = scope;
            this.permissions = permissions;
        }

        /**
         * Required scope.
         *
         * @return scope
         */
        public McpScope scope() {
            return scope;
        }

        /**
         * Required permissions (all).
         *
         * @return permissions
         */
        public List<Permission> permissions() {
            return permissions;
        }
    }

    /**
     * Validates components.
     */
    public McpToolRequirement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(resource, "resource");
        if (!TOOL_NAME.matcher(toolName).matches()) {
            throw new IllegalArgumentException("invalid MCP tool name");
        }
    }
}
