package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.annotations.Classification;
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
 * Team boundary that owns resources, grants and budgets ({@code dai_workspace}, F-62). Archived, never deleted.
 */
@Entity
@Table(name = "dai_workspace")
public class Workspace {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,62}");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "tenant_id", updatable = false)
    private @Nullable String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_clearance", nullable = false)
    private Classification clearance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkspaceStatus status;

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
    protected Workspace() {
    }

    private Workspace(String slug, String name, @Nullable String description, @Nullable String tenantId,
                      Classification clearance, @Nullable UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.tenantId = tenantId;
        this.clearance = clearance;
        this.status = WorkspaceStatus.ACTIVE;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
        this.updatedBy = createdBy;
    }

    /**
     * Creates an active workspace.
     *
     * @param slug        URL-safe unique key ({@code [a-z][a-z0-9-]{1,62}}), immutable
     * @param name        display name
     * @param description optional description
     * @param tenantId    optional host tenant the workspace is scoped to (SEC-01 §11), immutable
     * @param clearance   highest classification data of this workspace may carry (not {@code INHERIT})
     * @param createdBy   creating principal, {@code null} for bootstrap/system
     * @param now         creation time
     * @return the new workspace (not yet persisted)
     */
    public static Workspace create(String slug, String name, @Nullable String description, @Nullable String tenantId,
                                   Classification clearance, @Nullable UUID createdBy, Instant now) {
        Objects.requireNonNull(slug, "slug");
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("workspace slug must match [a-z][a-z0-9-]{1,62}: " + slug);
        }
        return new Workspace(slug, requireName(name), description, tenantId, requireClearance(clearance),
                createdBy, Objects.requireNonNull(now, "now"));
    }

    /**
     * Changes the descriptive attributes and clearance.
     *
     * @param newName        display name
     * @param newDescription optional description
     * @param newClearance   clearance (not {@code INHERIT})
     * @param by             acting principal
     * @param now            change time
     */
    public void update(String newName, @Nullable String newDescription, Classification newClearance, UUID by,
                       Instant now) {
        requireActive();
        this.name = requireName(newName);
        this.description = newDescription;
        this.clearance = requireClearance(newClearance);
        touch(by, now);
    }

    /**
     * Archives the workspace. Idempotent.
     *
     * @param by  acting principal
     * @param now change time
     */
    public void archive(UUID by, Instant now) {
        if (status != WorkspaceStatus.ARCHIVED) {
            this.status = WorkspaceStatus.ARCHIVED;
            touch(by, now);
        }
    }

    private void requireActive() {
        if (status != WorkspaceStatus.ACTIVE) {
            throw new IllegalStateException("workspace " + slug + " is archived");
        }
    }

    private void touch(UUID by, Instant now) {
        this.updatedBy = Objects.requireNonNull(by, "by");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("workspace name must have 1..200 characters");
        }
        return name;
    }

    private static Classification requireClearance(Classification clearance) {
        Objects.requireNonNull(clearance, "clearance");
        if (clearance == Classification.INHERIT) {
            throw new IllegalArgumentException("workspace clearance must be a concrete classification");
        }
        return clearance;
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public WorkspaceView view() {
        return new WorkspaceView(id, slug, name, description, tenantId, clearance, status, createdAt, updatedAt,
                rowVersion == null ? 0L : rowVersion);
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public WorkspaceStatus getStatus() {
        return status;
    }

    public long getRowVersion() {
        return rowVersion == null ? 0L : rowVersion;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Workspace other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
