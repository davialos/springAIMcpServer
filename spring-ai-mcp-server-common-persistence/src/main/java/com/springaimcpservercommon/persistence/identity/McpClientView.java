package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of an MCP client registration.
 *
 * @param id               registration id
 * @param workspaceId      workspace
 * @param issuer           authorization server issuer
 * @param clientId         OAuth client id
 * @param displayName      display name
 * @param registrationType how it was registered
 * @param status           approval status
 * @param approvedBy       approver, if approved
 * @param approvedAt       approval time, if approved
 * @param createdAt        registration time
 */
public record McpClientView(
        UUID id,
        UUID workspaceId,
        String issuer,
        String clientId,
        String displayName,
        McpRegistrationType registrationType,
        McpClientStatus status,
        @Nullable UUID approvedBy,
        @Nullable Instant approvedAt,
        Instant createdAt) {
}
