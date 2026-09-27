package com.springaimcpservercommon.persistence.proposal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Primary key {@code (proposal_id, seq)} of {@code dai_change_proposal_record}. */
@Embeddable
public class ChangeProposalRecordId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    /** For JPA only. */
    protected ChangeProposalRecordId() {
    }

    ChangeProposalRecordId(UUID proposalId, int seq) {
        this.proposalId = proposalId;
        this.seq = seq;
    }

    /** @return proposal id */
    public UUID getProposalId() {
        return proposalId;
    }

    /** @return record position */
    public int getSeq() {
        return seq;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposalRecordId other
                && seq == other.seq && Objects.equals(proposalId, other.proposalId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(proposalId, seq);
    }
}
