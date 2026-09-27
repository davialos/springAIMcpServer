package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
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
 * Stable identity of a configurable object ({@code dai_resource}). Its content lives in {@link ResourceRevision}s;
 * the published revision is not stored here (one owner per fact) — it is the revision in state PUBLISHED or
 * DEPRECATED.
 */
@Entity
@Table(name = "dai_resource")
public class Resource {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9_-]{1,62}");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ResourceKind kind;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ResourceStatus status;

    @Column(name = "suspended_reason")
    private @Nullable String suspendedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private @Nullable Long rowVersion;

    /** For JPA only. */
    protected Resource() {
    }

    private Resource(UUID workspaceId, ResourceKind kind, String slug, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.slug = slug;
        this.status = ResourceStatus.ACTIVE;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
        this.updatedBy = createdBy;
    }

    /**
     * Creates an active resource.
     *
     * @param workspaceId owning workspace
     * @param kind        resource kind
     * @param slug        slug unique per workspace and kind ({@code [a-z][a-z0-9_-]{1,62}})
     * @param createdBy   creating principal
     * @param now         creation time
     * @return the new resource (not yet persisted)
     */
    public static Resource create(UUID workspaceId, ResourceKind kind, String slug, UUID createdBy, Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(now, "now");
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("resource slug must match [a-z][a-z0-9_-]{1,62}: " + slug);
        }
        return new Resource(workspaceId, kind, slug, createdBy, now);
    }

    /**
     * Suspends the resource (it stays in the live set, but nodes must not serve it).
     *
     * @param reason why (mandatory, shown to operators)
     * @param by     acting principal
     * @param now    change time
     */
    public void suspend(String reason, UUID by, Instant now) {
        requireNotRetired();
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a suspension needs a reason");
        }
        this.status = ResourceStatus.SUSPENDED;
        this.suspendedReason = reason;
        touch(by, now);
    }

    /**
     * Lifts a suspension.
     *
     * @param by  acting principal
     * @param now change time
     */
    public void resume(UUID by, Instant now) {
        requireNotRetired();
        this.status = ResourceStatus.ACTIVE;
        this.suspendedReason = null;
        touch(by, now);
    }

    /**
     * Retires the resource permanently.
     *
     * @param by  acting principal
     * @param now change time
     */
    public void retire(UUID by, Instant now) {
        requireNotRetired();
        this.status = ResourceStatus.RETIRED;
        this.suspendedReason = null;
        touch(by, now);
    }

    /**
     * Fails if the resource is retired.
     *
     * @throws ConfigLifecycleException if retired
     */
    public void requireNotRetired() {
        if (status == ResourceStatus.RETIRED) {
            throw new ConfigLifecycleException("resource " + id + " is retired");
        }
    }

    private void touch(UUID by, Instant now) {
        this.updatedBy = Objects.requireNonNull(by, "by");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public ResourceView view() {
        return new ResourceView(id, workspaceId, kind, slug, status, suspendedReason, createdAt, createdBy, updatedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public ResourceKind getKind() {
        return kind;
    }

    public String getSlug() {
        return slug;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public @Nullable String getSuspendedReason() {
        return suspendedReason;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Resource other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
