package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.Objects;
import java.util.UUID;

/**
 * One manifest line of a snapshot generation: resource → revision ({@code dai_snapshot_entry}, append-only).
 * Every generation lists the full live set, not a delta.
 */
@Entity
@Immutable
@Table(name = "dai_snapshot_entry")
public class SnapshotEntry {

    @EmbeddedId
    private SnapshotEntryId id;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    /** For JPA only. */
    protected SnapshotEntry() {
    }

    /**
     * Creates an entry.
     *
     * @param generation snapshot generation
     * @param resourceId resource
     * @param revisionId live revision of the resource in that generation
     */
    public SnapshotEntry(long generation, UUID resourceId, UUID revisionId) {
        this.id = new SnapshotEntryId(generation, resourceId);
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
    }

    public SnapshotEntryId getId() {
        return id;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SnapshotEntry other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
