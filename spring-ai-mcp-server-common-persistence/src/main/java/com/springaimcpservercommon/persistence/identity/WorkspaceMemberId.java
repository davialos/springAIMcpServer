package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@code dai_workspace_member}: (workspace, principal, role). */
@Embeddable
public class WorkspaceMemberId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private FrameworkRole role;

    /** For JPA only. */
    protected WorkspaceMemberId() {
    }

    /**
     * Creates a key.
     *
     * @param workspaceId workspace
     * @param principalId member principal (user, group or service account)
     * @param role        workspace role
     */
    public WorkspaceMemberId(UUID workspaceId, UUID principalId, FrameworkRole role) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.role = Objects.requireNonNull(role, "role");
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public FrameworkRole getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof WorkspaceMemberId other
                && workspaceId.equals(other.workspaceId)
                && principalId.equals(other.principalId)
                && role == other.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, principalId, role);
    }
}
