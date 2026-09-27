package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A newly opened MCP session.
 *
 * @param sessionIdHash   {@code sha256:} of the {@code Mcp-Session-Id} (the raw id is never stored)
 * @param mcpClientId     registered MCP client, if known
 * @param workspaceId     workspace, if resolved
 * @param principalId     caller
 * @param transport       transport
 * @param protocolVersion negotiated MCP protocol version, if known
 * @param clientName      client name from {@code initialize}, if any
 * @param clientVersion   client version from {@code initialize}, if any
 */
public record NewMcpSession(
        String sessionIdHash,
        @Nullable UUID mcpClientId,
        @Nullable UUID workspaceId,
        UUID principalId,
        McpTransport transport,
        @Nullable String protocolVersion,
        @Nullable String clientName,
        @Nullable String clientVersion) {
}
