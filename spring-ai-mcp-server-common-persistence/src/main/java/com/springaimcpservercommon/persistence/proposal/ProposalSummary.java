package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.time.Instant;
import java.util.UUID;

/**
 * List view of a proposal (no records, history or approvals), used by pending lists and the approval inbox.
 *
 * @param id                proposal id
 * @param workspaceId       workspace
 * @param ownerId           owner
 * @param targetRef         target
 * @param changeKind        kind of change
 * @param state             state
 * @param summary           summary
 * @param requiredApprovals approvals required
 * @param createdAt         creation time
 * @param expiresAt         expiry time
 * @param rowVersion        optimistic-lock version
 */
public record ProposalSummary(
        UUID id,
        UUID workspaceId,
        UUID ownerId,
        CatalogElementRef targetRef,
        ChangeKind changeKind,
        ProposalState state,
        String summary,
        int requiredApprovals,
        Instant createdAt,
        Instant expiresAt,
        long rowVersion) {

    /**
     * Summarises a proposal without touching its lazy collections.
     *
     * @param p the proposal
     * @return the summary
     */
    public static ProposalSummary of(ChangeProposal p) {
        return new ProposalSummary(p.getId(), p.getWorkspaceId(), p.getOwnerId(), p.getTargetRef(), p.getChangeKind(),
                p.getState(), p.getSummary(), p.getRequiredApprovals(), p.getCreatedAt(), p.getExpiresAt(),
                p.getRowVersion());
    }
}
