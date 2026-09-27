package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a service account.
 *
 * @param id          account id
 * @param principalId its principal reference
 * @param workspaceId owning workspace
 * @param name        name, unique within the workspace
 * @param description optional description
 * @param status      status
 * @param createdAt   creation time
 */
public record ServiceAccountView(
        UUID id,
        UUID principalId,
        UUID workspaceId,
        String name,
        @Nullable String description,
        ServiceAccountStatus status,
        Instant createdAt) {
}
