package com.springaimcpservercommon.persistence.config;

/**
 * Result of recording a review.
 *
 * @param review    the recorded review
 * @param revision  the revision after applying the review (APPROVED once the quorum is reached, REJECTED on a
 *                  rejection or change request, else still IN_REVIEW)
 * @param approvals number of approving reviews so far
 */
public record ReviewOutcome(ReviewView review, RevisionView revision, long approvals) {
}
