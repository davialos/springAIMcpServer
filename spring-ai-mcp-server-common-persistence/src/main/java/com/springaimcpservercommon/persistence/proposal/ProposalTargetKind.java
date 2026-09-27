package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

/** How a proposal is applied ({@code ck_change_proposal_target_kind}, LLD-11 §4). */
public enum ProposalTargetKind {
    /** Calls a host service method through its Spring proxy; target is an {@code op:} reference. */
    HOST_OPERATION(CatalogElementRef.Kind.OP),
    /** Writes an allow-listed entity through the EntityManager; target is an {@code entity:} reference. */
    ENTITY_WRITE(CatalogElementRef.Kind.ENTITY);

    private final CatalogElementRef.Kind refKind;

    ProposalTargetKind(CatalogElementRef.Kind refKind) {
        this.refKind = refKind;
    }

    /**
     * The catalog element kind the target reference must have ({@code ck_change_proposal_target_ref}).
     *
     * @return required reference kind
     */
    public CatalogElementRef.Kind refKind() {
        return refKind;
    }
}
