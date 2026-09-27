package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a workspace membership.
 *
 * @param workspaceId workspace
 * @param principalId member principal
 * @param role        workspace role
 * @param grantedBy   granting principal, if recorded
 * @param grantedAt   grant time
 * @param expiresAt   optional expiry
 */
public record MemberView(
        UUID workspaceId,
        UUID principalId,
        FrameworkRole role,
        @Nullable UUID grantedBy,
        Instant grantedAt,
        @Nullable Instant expiresAt) {
}
