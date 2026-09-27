package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A published snapshot generation ({@code dai_snapshot}, append-only, LLD-09 §4).
 *
 * <p>Read-only mapping: rows are inserted by {@link ConfigStore} with native SQL, because {@code generation} is a
 * {@code GENERATED ALWAYS} identity that the store assigns explicitly ({@code OVERRIDING SYSTEM VALUE}) as
 * {@code max + 1} under the publish lock, which keeps generations contiguous even when a publish rolls back.
 */
@Entity
@Immutable
@Table(name = "dai_snapshot")
public class Snapshot {

    @Id
    @Column(name = "generation", nullable = false, updatable = false)
    private Long generation;

    @Column(name = "published_by", nullable = false, updatable = false)
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "reason", updatable = false)
    private @Nullable String reason;

    @Column(name = "rollback_of_generation", updatable = false)
    private @Nullable Long rollbackOfGeneration;

    @Column(name = "manifest_hash", nullable = false, updatable = false)
    private String manifestHash;

    /** For JPA only. */
    protected Snapshot() {
    }

    public long getGeneration() {
        return generation;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public @Nullable Long getRollbackOfGeneration() {
        return rollbackOfGeneration;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Snapshot other && generation.equals(other.getGeneration()));
    }

    @Override
    public int hashCode() {
        return generation.hashCode();
    }
}
