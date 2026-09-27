package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Direct workspace membership ({@code dai_workspace_member}). The principal may be an IdP group, so group
 * membership in the IdP drives access (SEC-01 §1.2).
 */
@Entity
@Table(name = "dai_workspace_member")
public class WorkspaceMember {

    @EmbeddedId
    private WorkspaceMemberId id;

    @Column(name = "granted_by", updatable = false)
    private @Nullable UUID grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private @Nullable Instant expiresAt;

    /** For JPA only. */
    protected WorkspaceMember() {
    }

    private WorkspaceMember(WorkspaceMemberId id, @Nullable UUID grantedBy, Instant grantedAt,
                            @Nullable Instant expiresAt) {
        this.id = id;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Creates a membership.
     *
     * @param workspaceId workspace
     * @param principalId member principal
     * @param role        workspace role; global-only roles ({@link FrameworkRole#globalOnly()}) are rejected
     * @param grantedBy   granting principal, {@code null} for bootstrap
     * @param expiresAt   optional expiry, must be after {@code now}
     * @param now         grant time
     * @return the new membership (not yet persisted)
     */
    public static WorkspaceMember grant(UUID workspaceId, UUID principalId, FrameworkRole role,
                                        @Nullable UUID grantedBy, @Nullable Instant expiresAt, Instant now) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(now, "now");
        if (role.globalOnly()) {
            throw new IllegalArgumentException(role + " is a global role and cannot be a workspace membership");
        }
        requireExpiryAfter(expiresAt, now);
        return new WorkspaceMember(new WorkspaceMemberId(workspaceId, principalId, role), grantedBy, now, expiresAt);
    }

    /**
     * Changes the expiry of an existing membership.
     *
     * @param newExpiresAt new expiry or {@code null} for none; must be after the original grant time
     */
    public void changeExpiry(@Nullable Instant newExpiresAt) {
        requireExpiryAfter(newExpiresAt, grantedAt);
        this.expiresAt = newExpiresAt;
    }

    private static void requireExpiryAfter(@Nullable Instant expiresAt, Instant reference) {
        if (expiresAt != null && !expiresAt.isAfter(reference)) {
            throw new IllegalArgumentException("membership expiry must be after " + reference);
        }
    }

    /**
     * Whether the membership is in force at the given time.
     *
     * @param now evaluation time
     * @return {@code true} if not expired
     */
    public boolean isEffectiveAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public MemberView view() {
        return new MemberView(id.getWorkspaceId(), id.getPrincipalId(), id.getRole(), grantedBy, grantedAt, expiresAt);
    }

    public WorkspaceMemberId getId() {
        return id;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof WorkspaceMember other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
