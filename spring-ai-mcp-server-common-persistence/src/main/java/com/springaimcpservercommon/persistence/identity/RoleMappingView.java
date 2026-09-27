package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a role mapping.
 *
 * @param id          mapping id
 * @param rule        matching rule
 * @param enabled     whether the mapping is evaluated
 * @param description optional description
 * @param createdAt   creation time
 * @param updatedAt   last change time
 * @param rowVersion  optimistic-lock version, to be passed back on update
 */
public record RoleMappingView(
        UUID id,
        RoleMappingRule rule,
        boolean enabled,
        @Nullable String description,
        Instant createdAt,
        Instant updatedAt,
        long rowVersion) {
}
