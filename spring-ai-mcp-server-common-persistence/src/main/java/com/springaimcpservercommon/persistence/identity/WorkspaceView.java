package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a workspace.
 *
 * @param id          workspace id
 * @param slug        unique slug
 * @param name        display name
 * @param description optional description
 * @param tenantId    optional host tenant
 * @param clearance   highest classification allowed
 * @param status      status
 * @param createdAt   creation time
 * @param updatedAt   last change time
 * @param rowVersion  optimistic-lock version, to be passed back on update
 */
public record WorkspaceView(
        UUID id,
        String slug,
        String name,
        @Nullable String description,
        @Nullable String tenantId,
        Classification clearance,
        WorkspaceStatus status,
        Instant createdAt,
        Instant updatedAt,
        long rowVersion) {
}
