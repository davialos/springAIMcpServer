package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable view of an MCP client consent.
 *
 * @param id          consent id
 * @param mcpClientId client registration
 * @param principalId consenting principal
 * @param scopes      consented scopes
 * @param grantedAt   grant time
 * @param revokedAt   revocation time, {@code null} while active
 */
public record McpConsentView(
        UUID id,
        UUID mcpClientId,
        UUID principalId,
        Set<McpConsentScope> scopes,
        Instant grantedAt,
        @Nullable Instant revokedAt) {

    /** Copies the scope set. */
    public McpConsentView {
        scopes = Set.copyOf(scopes);
    }

    /**
     * Whether the consent is active.
     *
     * @return {@code true} if not revoked
     */
    public boolean active() {
        return revokedAt == null;
    }
}
