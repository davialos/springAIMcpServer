package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a resource.
 *
 * @param id              resource id
 * @param workspaceId     owning workspace
 * @param kind            kind
 * @param slug            slug, unique per workspace and kind
 * @param status          operational status
 * @param suspendedReason reason while SUSPENDED
 * @param createdAt       creation time
 * @param createdBy       creating principal
 * @param updatedAt       last change time
 */
public record ResourceView(
        UUID id,
        UUID workspaceId,
        ResourceKind kind,
        String slug,
        ResourceStatus status,
        @Nullable String suspendedReason,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt) {
}
