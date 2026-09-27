package com.springaimcpservercommon.security.port;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Port to {@code dai_grant} (SEC-01 §7 step 3).
 */
public interface GrantSource {

    /**
     * Returns the grants of a workspace held by any of the given subjects for one permission — resource-specific,
     * pattern and workspace-wide grants alike. Implementations may omit expired grants; the engine re-checks expiry.
     *
     * @param workspaceId workspace of the resource (or of the workspace-level check)
     * @param principalIds the caller's principal id, its group principal ids
     * @param permission  permission value ({@code <resource>:<action>})
     * @return matching grant rows
     */
    List<GrantRecord> findGrants(UUID workspaceId, Set<UUID> principalIds, String permission);

    /**
     * One {@code dai_grant} row.
     *
     * @param id              grant id
     * @param workspaceId     workspace
     * @param principalId     subject (user, group or service-account principal)
     * @param permission      permission value
     * @param resourceId      specific resource, or {@code null}
     * @param resourcePattern glob over {@code <kind>/<slug>} (e.g. {@code query/orders-*}), or {@code null}
     * @param conditionsJson  ABAC conditions ({@code jsonb} as text), or {@code null}
     * @param expiresAt       expiry, or {@code null}
     */
    record GrantRecord(UUID id, UUID workspaceId, UUID principalId, String permission, @Nullable UUID resourceId,
                       @Nullable String resourcePattern, @Nullable String conditionsJson, @Nullable Instant expiresAt) {
        /**
         * Validates required components.
         */
        public GrantRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(permission, "permission");
        }
    }
}
