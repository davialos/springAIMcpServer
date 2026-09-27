package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A reviewer's decision on a submitted revision ({@code dai_review}). Reviews are never changed; one per reviewer
 * and revision ({@code uq_review_reviewer}).
 */
@Entity
@Immutable
@Table(name = "dai_review")
public class Review {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private ReviewDecision decision;

    @Column(name = "comment", updatable = false)
    private @Nullable String comment;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    /** For JPA only. */
    protected Review() {
    }

    private Review(UUID revisionId, UUID reviewerId, ReviewDecision decision, @Nullable String comment,
                   Instant decidedAt) {
        this.id = Ids.newId();
        this.revisionId = revisionId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.comment = comment;
        this.decidedAt = decidedAt;
    }

    /**
     * Records a decision, enforcing the four-eyes rule in Java (reviewer ≠ author) and the review preconditions.
     * The caller applies the effect on the revision (approve when the quorum is reached, reject otherwise).
     *
     * @param revision   the reviewed revision; must be IN_REVIEW
     * @param reviewerId reviewing principal
     * @param decision   decision
     * @param comment    mandatory unless approving
     * @param now        decision time
     * @return the new review (not yet persisted)
     * @throws SegregationOfDutiesException if the reviewer authored the revision
     * @throws ConfigLifecycleException     if the revision is not in review
     */
    public static Review record(ResourceRevision revision, UUID reviewerId, ReviewDecision decision,
                                @Nullable String comment, Instant now) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(reviewerId, "reviewerId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(now, "now");
        if (revision.getAuthorId().equals(reviewerId)) {
            throw new SegregationOfDutiesException("the author cannot review revision " + revision.getId());
        }
        if (revision.getState() != RevisionState.IN_REVIEW) {
            throw new ConfigLifecycleException("revision " + revision.getId() + " is " + revision.getState()
                    + ", not IN_REVIEW");
        }
        if (decision != ReviewDecision.APPROVED && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException(decision + " requires a comment");
        }
        return new Review(revision.getId(), reviewerId, decision, comment, now);
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public ReviewView view() {
        return new ReviewView(id, revisionId, reviewerId, decision, comment, decidedAt);
    }

    public UUID getId() {
        return id;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Review other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
