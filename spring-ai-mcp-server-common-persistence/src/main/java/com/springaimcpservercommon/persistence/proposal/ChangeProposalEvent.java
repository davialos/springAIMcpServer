package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a proposal's history ({@code dai_change_proposal_event}, append-only by trigger). {@code seq} is
 * contiguous per proposal starting at 0; {@code from_state} is null only for the creating event. An event whose
 * {@code from_state} equals its {@code to_state} records a change that did not move the state (e.g. an approval
 * requirement raised by policy).
 */
@Entity
@Immutable
@Table(name = "dai_change_proposal_event")
public class ChangeProposalEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, updatable = false)
    private ChangeProposal proposal;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", updatable = false)
    private @Nullable ProposalState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, updatable = false)
    private ProposalState toState;

    @Column(name = "actor_id", updatable = false)
    private @Nullable UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    private @Nullable String details;

    /** For JPA only. */
    protected ChangeProposalEvent() {
    }

    static ChangeProposalEvent of(ChangeProposal proposal, int seq, @Nullable ProposalState from, ProposalState to,
                                  @Nullable UUID actorId, Instant now, @Nullable String detailsJson) {
        ChangeProposalEvent e = new ChangeProposalEvent();
        e.id = Ids.newId();
        e.proposal = proposal;
        e.seq = seq;
        e.fromState = from;
        e.toState = to;
        e.actorId = actorId;
        e.occurredAt = UtcTimes.micros(now);
        e.details = Checks.optionalJson(detailsJson, "details");
        return e;
    }

    /** @return event id */
    public UUID getId() {
        return id;
    }

    /** @return position in the proposal history (0 = creation) */
    public int getSeq() {
        return seq;
    }

    /** @return previous state, {@code null} for the creating event */
    public @Nullable ProposalState getFromState() {
        return fromState;
    }

    /** @return new state */
    public ProposalState getToState() {
        return toState;
    }

    /** @return acting principal, {@code null} for system actions (expiry, reconciliation) */
    public @Nullable UUID getActorId() {
        return actorId;
    }

    /** @return time */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return details JSON, if any */
    public @Nullable String getDetailsJson() {
        return details;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposalEvent other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
