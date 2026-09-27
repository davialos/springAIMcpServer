package com.springaimcpservercommon.persistence.config;

/** Decision of a review (matches {@code ck_review_decision}). */
public enum ReviewDecision {
    /** Approves the revision. */
    APPROVED,
    /** Rejects the revision; a comment is mandatory. */
    REJECTED,
    /** Asks for changes; a comment is mandatory. Ends the review like a rejection (revisions are immutable). */
    CHANGES_REQUESTED
}
