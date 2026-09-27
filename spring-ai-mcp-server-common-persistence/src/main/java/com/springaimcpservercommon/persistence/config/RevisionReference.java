package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A catalog element a revision depends on, pinned with its signature hash for drift detection
 * ({@code dai_revision_reference}, LLD-03 §6). Only host code elements (entity, attr, op, ctx) can be pinned, as
 * enforced by {@code ck_revision_reference_ref}.
 */
@Entity
@Table(name = "dai_revision_reference")
public class RevisionReference {

    /** Element kinds that can be pinned. */
    public static final Set<CatalogElementRef.Kind> PINNABLE_KINDS = EnumSet.of(
            CatalogElementRef.Kind.ENTITY, CatalogElementRef.Kind.ATTR, CatalogElementRef.Kind.OP,
            CatalogElementRef.Kind.CTX);

    @EmbeddedId
    private RevisionReferenceId id;

    @Column(name = "signature_hash", nullable = false, updatable = false)
    private String signatureHash;

    /** For JPA only. */
    protected RevisionReference() {
    }

    private RevisionReference(RevisionReferenceId id, String signatureHash) {
        this.id = id;
        this.signatureHash = signatureHash;
    }

    /**
     * Creates a pinned reference.
     *
     * @param revisionId    revision (must be a draft; checked by the caller)
     * @param elementRef    pinned element; kind must be in {@link #PINNABLE_KINDS}
     * @param signatureHash signature hash of the element at authoring time ({@code sha256:<hex>})
     * @return the new reference (not yet persisted)
     */
    public static RevisionReference pin(UUID revisionId, CatalogElementRef elementRef, String signatureHash) {
        Objects.requireNonNull(elementRef, "elementRef");
        Objects.requireNonNull(signatureHash, "signatureHash");
        if (!PINNABLE_KINDS.contains(elementRef.kind())) {
            throw new IllegalArgumentException("only entity/attr/op/ctx elements can be pinned: " + elementRef);
        }
        if (!Sha256.isValid(signatureHash)) {
            throw new IllegalArgumentException("signature hash must be sha256:<64 hex>");
        }
        return new RevisionReference(new RevisionReferenceId(revisionId, elementRef.toString()), signatureHash);
    }

    public RevisionReferenceId getId() {
        return id;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RevisionReference other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
