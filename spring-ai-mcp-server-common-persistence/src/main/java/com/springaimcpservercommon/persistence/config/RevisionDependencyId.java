package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@code dai_revision_dependency}: (revision, resource it depends on). */
@Embeddable
public class RevisionDependencyId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "depends_on_resource_id", nullable = false, updatable = false)
    private UUID dependsOnResourceId;

    /** For JPA only. */
    protected RevisionDependencyId() {
    }

    /**
     * Creates a key.
     *
     * @param revisionId          depending revision
     * @param dependsOnResourceId resource it uses
     */
    public RevisionDependencyId(UUID revisionId, UUID dependsOnResourceId) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.dependsOnResourceId = Objects.requireNonNull(dependsOnResourceId, "dependsOnResourceId");
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getDependsOnResourceId() {
        return dependsOnResourceId;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RevisionDependencyId other
                && revisionId.equals(other.revisionId) && dependsOnResourceId.equals(other.dependsOnResourceId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(revisionId, dependsOnResourceId);
    }
}
