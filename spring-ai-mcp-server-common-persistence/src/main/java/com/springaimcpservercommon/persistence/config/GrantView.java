package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a grant.
 *
 * @param id             grant id
 * @param workspaceId    workspace
 * @param principalId    grantee
 * @param permission     permission ({@code resource:action})
 * @param target         workspace-wide, resource or pattern
 * @param conditionsJson optional ABAC conditions (JSON object)
 * @param expiresAt      optional expiry
 * @param createdAt      creation time
 * @param createdBy      granting principal
 */
public record GrantView(
        UUID id,
        UUID workspaceId,
        UUID principalId,
        String permission,
        GrantTarget target,
        @Nullable String conditionsJson,
        @Nullable Instant expiresAt,
        Instant createdAt,
        UUID createdBy) {
}
