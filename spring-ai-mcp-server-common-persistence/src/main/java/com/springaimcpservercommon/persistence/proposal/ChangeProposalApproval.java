package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A second-person decision on a proposal ({@code dai_change_proposal_approval}); one per approver, final once made.
 * The database trigger {@code trg_change_proposal_approval_sod} additionally rejects owner approvals.
 */
@Entity
@Immutable
@Table(name = "dai_change_proposal_approval")
public class ChangeProposalApproval {

    /** Longest comment accepted. */
    public static final int MAX_COMMENT_LENGTH = 2000;

    @EmbeddedId
    private ChangeProposalApprovalId id;

    @MapsId("proposalId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, updatable = false)
    private ChangeProposal proposal;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private ApprovalDecision decision;

    @Column(name = "comment", updatable = false)
    private @Nullable String comment;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    /** For JPA only. */
    protected ChangeProposalApproval() {
    }

    static ChangeProposalApproval of(ChangeProposal proposal, UUID approverId, ApprovalDecision decision,
                                     @Nullable String comment, Instant now) {
        ChangeProposalApproval a = new ChangeProposalApproval();
        a.id = new ChangeProposalApprovalId(proposal.getId(), approverId);
        a.proposal = proposal;
        a.decision = Checks.required(decision, "decision");
        a.comment = Checks.optionalText(comment, "comment", MAX_COMMENT_LENGTH);
        if (decision == ApprovalDecision.REJECTED && a.comment == null) {
            throw new IllegalArgumentException("a rejection needs a comment");
        }
        a.decidedAt = UtcTimes.micros(now);
        return a;
    }

    /** @return approver principal id */
    public UUID getApproverId() {
        return id.getApproverId();
    }

    /** @return decision */
    public ApprovalDecision getDecision() {
        return decision;
    }

    /** @return comment, if any */
    public @Nullable String getComment() {
        return comment;
    }

    /** @return decision time */
    public Instant getDecidedAt() {
        return decidedAt;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposalApproval other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
