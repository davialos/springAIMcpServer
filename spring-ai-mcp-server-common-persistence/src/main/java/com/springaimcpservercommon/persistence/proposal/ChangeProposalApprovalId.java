package com.springaimcpservercommon.persistence.proposal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Primary key {@code (proposal_id, approver_id)} of {@code dai_change_proposal_approval}. */
@Embeddable
public class ChangeProposalApprovalId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Column(name = "approver_id", nullable = false, updatable = false)
    private UUID approverId;

    /** For JPA only. */
    protected ChangeProposalApprovalId() {
    }

    ChangeProposalApprovalId(UUID proposalId, UUID approverId) {
        this.proposalId = proposalId;
        this.approverId = approverId;
    }

    /** @return proposal id */
    public UUID getProposalId() {
        return proposalId;
    }

    /** @return approver principal id */
    public UUID getApproverId() {
        return approverId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposalApprovalId other
                && Objects.equals(proposalId, other.proposalId) && Objects.equals(approverId, other.approverId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(proposalId, approverId);
    }
}
