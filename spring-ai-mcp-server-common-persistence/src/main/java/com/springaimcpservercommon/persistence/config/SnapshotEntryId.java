package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@code dai_snapshot_entry}: (generation, resource). */
@Embeddable
public class SnapshotEntryId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "generation", nullable = false, updatable = false)
    private long generation;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    /** For JPA only. */
    protected SnapshotEntryId() {
    }

    /**
     * Creates a key.
     *
     * @param generation snapshot generation
     * @param resourceId resource
     */
    public SnapshotEntryId(long generation, UUID resourceId) {
        this.generation = generation;
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
    }

    public long getGeneration() {
        return generation;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SnapshotEntryId other
                && generation == other.generation && resourceId.equals(other.resourceId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(generation, resourceId);
    }
}
