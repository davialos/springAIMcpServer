package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.SubjectType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One authorization decision, emitted to {@link AuthorizationAuditListener}s. Contains identifiers only — no claims,
 * tokens or request payloads.
 *
 * @param decidedAt   decision time
 * @param principalId caller principal id, {@code null} if not authenticated
 * @param subjectType caller subject type, {@code null} if not authenticated
 * @param permission  permission value
 * @param workspaceId workspace, if any
 * @param resourceId  resource, if any
 * @param toolName    tool, if any
 * @param outcome     the outcome
 */
public record AuthorizationDecisionEvent(
        Instant decidedAt,
        @Nullable UUID principalId,
        @Nullable SubjectType subjectType,
        String permission,
        @Nullable UUID workspaceId,
        @Nullable UUID resourceId,
        @Nullable String toolName,
        AuthorizationOutcome outcome) {

    /**
     * Validates components.
     */
    public AuthorizationDecisionEvent {
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(outcome, "outcome");
    }
}
