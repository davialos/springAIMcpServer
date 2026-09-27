package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Another resource a revision uses, e.g. endpoint → query, agent → tool binding ({@code dai_revision_dependency}).
 * Used for impact analysis and to keep the live set closed: a live revision may only depend on resources that are
 * live themselves (checked on every generation by {@link ConfigStore}).
 */
@Entity
@Table(name = "dai_revision_dependency")
public class RevisionDependency {

    @EmbeddedId
    private RevisionDependencyId id;

    /** For JPA only. */
    protected RevisionDependency() {
    }

    /**
     * Creates a dependency.
     *
     * @param revisionId          depending revision (must be a draft; checked by the caller)
     * @param dependsOnResourceId used resource
     */
    public RevisionDependency(UUID revisionId, UUID dependsOnResourceId) {
        this.id = new RevisionDependencyId(revisionId, dependsOnResourceId);
    }

    public RevisionDependencyId getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RevisionDependency other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
