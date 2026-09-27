package com.springaimcpservercommon.security.port;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port to the approved MCP client registry ({@code dai_mcp_client}, {@code dai_mcp_client_consent*}, LLD-07 §5.3).
 */
public interface McpClientRegistryPort {

    /**
     * Finds an {@code APPROVED} client of a workspace.
     *
     * @param workspaceId workspace served by the MCP endpoint
     * @param issuer      token issuer
     * @param clientId    OAuth client id ({@code azp} / {@code client_id})
     * @return the {@code dai_mcp_client.id}, empty if unknown, pending or revoked
     */
    Optional<UUID> findApprovedClient(UUID workspaceId, String issuer, String clientId);

    /**
     * Whether the principal has an active (not revoked) consent for the client.
     *
     * @param mcpClientId client id
     * @param principalId user principal
     * @return {@code true} if consented
     */
    boolean hasActiveConsent(UUID mcpClientId, UUID principalId);

    /**
     * Records first use of an approved client by a user (audited, revocable in the dashboard). Must be idempotent
     * ({@code uq_mcp_client_consent_active}).
     *
     * @param mcpClientId client id
     * @param principalId user principal
     * @param scopes      MCP scopes of the token ({@code dai.mcp.*} only)
     */
    void recordConsent(UUID mcpClientId, UUID principalId, Set<String> scopes);
}
