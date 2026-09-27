package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who may do what on which resource ({@code dai_grant}, SEC-01 §7). Grants are immutable; changing one means
 * revoking (deleting) it and creating a new one, both audited by the admin API. The JPQL entity name is
 * {@code DaiGrant} because {@code GRANT} is an SQL keyword.
 */
@Entity(name = "DaiGrant")
@Immutable
@Table(name = "dai_grant")
public class Grant {

    /** Format of a permission ({@code resource:action}). */
    public static final Pattern PERMISSION = Pattern.compile("[a-z][a-z-]*:[a-z][a-z-]*");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "permission", nullable = false, updatable = false)
    private String permission;

    @Column(name = "resource_id", updatable = false)
    private @Nullable UUID resourceId;

    @Column(name = "resource_pattern", updatable = false)
    private @Nullable String resourcePattern;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", updatable = false)
    private @Nullable String conditions;

    @Column(name = "expires_at", updatable = false)
    private @Nullable Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /** For JPA only. */
    protected Grant() {
    }

    private Grant(UUID workspaceId, UUID principalId, String permission, GrantTarget target,
                  @Nullable String conditions, @Nullable Instant expiresAt, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.workspaceId = workspaceId;
        this.principalId = principalId;
        this.permission = permission;
        switch (target) {
            case GrantTarget.WorkspaceWide ignored -> {
                // neither resource nor pattern
            }
            case GrantTarget.OnResource r -> this.resourceId = r.resourceId();
            case GrantTarget.OnPattern p -> this.resourcePattern = p.pattern();
        }
        this.conditions = conditions;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.createdBy = createdBy;
    }

    /**
     * Creates a grant.
     *
     * @param workspaceId    workspace the grant belongs to
     * @param principalId    grantee (user, group, service account or MCP client principal)
     * @param permission     permission ({@code resource:action}, e.g. {@code agent:invoke})
     * @param target         workspace-wide, one resource or a resource pattern
     * @param conditionsJson optional ABAC conditions as a JSON object (stored canonicalised)
     * @param expiresAt      optional expiry, after {@code now}
     * @param createdBy      granting principal
     * @param now            creation time
     * @return the new grant (not yet persisted)
     */
    public static Grant create(UUID workspaceId, UUID principalId, String permission, GrantTarget target,
                               @Nullable String conditionsJson, @Nullable Instant expiresAt, UUID createdBy,
                               Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(now, "now");
        if (!PERMISSION.matcher(permission).matches()) {
            throw new IllegalArgumentException("permission must match resource:action — " + permission);
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("grant expiry must be in the future");
        }
        String conditions = conditionsJson == null ? null : CanonicalSpec.canonicalObject(conditionsJson);
        return new Grant(workspaceId, principalId, permission, target, conditions, expiresAt, createdBy, now);
    }

    /**
     * The target of this grant.
     *
     * @return the target
     */
    public GrantTarget target() {
        if (resourceId != null) {
            return new GrantTarget.OnResource(resourceId);
        }
        if (resourcePattern != null) {
            return new GrantTarget.OnPattern(resourcePattern);
        }
        return new GrantTarget.WorkspaceWide();
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public GrantView view() {
        return new GrantView(id, workspaceId, principalId, permission, target(), conditions, expiresAt, createdAt,
                createdBy);
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Grant other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
