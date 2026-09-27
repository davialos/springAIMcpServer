package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Workspace-owned non-human identity ({@code dai_service_account}, SEC-01 §9). Each account has its own
 * {@link Principal} (subject type {@code SERVICE_ACCOUNT}, issuer {@value Principal#FRAMEWORK_ISSUER}, external id =
 * the account id) so grants and audit reference it like any other subject.
 */
@Entity
@Table(name = "dai_service_account")
public class ServiceAccount {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ServiceAccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private @Nullable Long rowVersion;

    /** For JPA only. */
    protected ServiceAccount() {
    }

    private ServiceAccount(UUID id, UUID principalId, UUID workspaceId, String name, @Nullable String description,
                           @Nullable UUID createdBy, Instant now) {
        this.id = id;
        this.principalId = principalId;
        this.workspaceId = workspaceId;
        this.name = name;
        this.description = description;
        this.status = ServiceAccountStatus.ACTIVE;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
        this.updatedBy = createdBy;
    }

    /**
     * Creates the account together with its principal reference.
     *
     * @param workspaceId owning workspace
     * @param name        name unique within the workspace ({@code [a-z][a-z0-9-]{1,62}})
     * @param description optional description
     * @param createdBy   creating principal
     * @param now         creation time
     * @return both new, not yet persisted objects; persist the principal first
     */
    public static Created create(UUID workspaceId, String name, @Nullable String description,
                                 @Nullable UUID createdBy, Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(now, "now");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("service account name must match [a-z][a-z0-9-]{1,62}: " + name);
        }
        UUID id = Ids.newId();
        Principal principal = Principal.create(SubjectType.SERVICE_ACCOUNT,
                Principal.FRAMEWORK_ISSUER, id.toString(), name, now);
        return new Created(new ServiceAccount(id, principal.getId(), workspaceId, name, description, createdBy, now),
                principal);
    }

    /**
     * A new account and its principal.
     *
     * @param account   the account
     * @param principal its principal reference
     */
    public record Created(ServiceAccount account, Principal principal) {
    }

    /**
     * Disables the account. Idempotent.
     *
     * @param by  acting principal
     * @param now change time
     */
    public void disable(UUID by, Instant now) {
        changeStatus(ServiceAccountStatus.DISABLED, by, now);
    }

    /**
     * Re-enables the account. Idempotent.
     *
     * @param by  acting principal
     * @param now change time
     */
    public void enable(UUID by, Instant now) {
        changeStatus(ServiceAccountStatus.ACTIVE, by, now);
    }

    private void changeStatus(ServiceAccountStatus target, UUID by, Instant now) {
        if (status != target) {
            this.status = target;
            this.updatedBy = Objects.requireNonNull(by, "by");
            this.updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public ServiceAccountView view() {
        return new ServiceAccountView(id, principalId, workspaceId, name, description, status, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public ServiceAccountStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ServiceAccount other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
