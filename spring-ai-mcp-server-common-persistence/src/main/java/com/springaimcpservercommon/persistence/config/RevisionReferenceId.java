package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@code dai_revision_reference}: (revision, catalog element ref text). */
@Embeddable
public class RevisionReferenceId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "element_ref", nullable = false, updatable = false)
    private String elementRef;

    /** For JPA only. */
    protected RevisionReferenceId() {
    }

    /**
     * Creates a key.
     *
     * @param revisionId revision
     * @param elementRef textual {@code CatalogElementRef}
     */
    public RevisionReferenceId(UUID revisionId, String elementRef) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.elementRef = Objects.requireNonNull(elementRef, "elementRef");
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public String getElementRef() {
        return elementRef;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RevisionReferenceId other
                && revisionId.equals(other.revisionId) && elementRef.equals(other.elementRef));
    }

    @Override
    public int hashCode() {
        return Objects.hash(revisionId, elementRef);
    }
}
