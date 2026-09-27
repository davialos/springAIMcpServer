package com.springaimcpservercommon.persistence.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * State of a resource revision (matches {@code ck_resource_revision_state}) and the allowed transitions
 * (LLD-09 §2):
 *
 * <pre>
 *  DRAFT ──submit──► IN_REVIEW ──approve──► APPROVED ──publish──► PUBLISHED ──deprecate──► DEPRECATED ──retire──► RETIRED
 *                      │ reject /                │ expire              │ newer publish            │ newer publish
 *                      │ changes requested       ▼                     ▼  or rollback             ▼  or rollback
 *                      ▼                       STALE               SUPERSEDED ◄───────────────────┘
 *                   REJECTED                                           │ rollback (republish)
 *                                                                      └──────────► PUBLISHED
 * </pre>
 *
 * <p>Only DRAFT is editable; no state leads back to DRAFT (the V2 trigger enforces both). A rejected revision is
 * reworked by creating a new draft {@code based_on} it. The live set of a resource is its revision in PUBLISHED or
 * DEPRECATED state (DEPRECATED is still served, flagged for removal); SUPERSEDED means "was live, no longer is" and
 * is the only state a rollback can republish.
 */
public enum RevisionState {
    /** Being authored; the only editable state. */
    DRAFT,
    /** Submitted; waiting for reviews. */
    IN_REVIEW,
    /** Approved; waiting to be published. */
    APPROVED,
    /** Live. At most one per resource ({@code uq_resource_revision_one_published}). */
    PUBLISHED,
    /** Replaced by a newer publish or removed by a rollback; can be republished by a rollback. */
    SUPERSEDED,
    /** Rejected (or changes requested) during review. Terminal. */
    REJECTED,
    /** Approval expired before publish. Terminal. */
    STALE,
    /** Still live, but scheduled for removal. */
    DEPRECATED,
    /** Removed for good. Terminal. */
    RETIRED;

    /**
     * States that are part of the live set served by nodes.
     *
     * @return PUBLISHED and DEPRECATED
     */
    public static Set<RevisionState> liveStates() {
        return EnumSet.of(PUBLISHED, DEPRECATED);
    }

    /**
     * Whether this state belongs to the live set.
     *
     * @return {@code true} for PUBLISHED and DEPRECATED
     */
    public boolean isLive() {
        return this == PUBLISHED || this == DEPRECATED;
    }

    /**
     * Whether the transition to {@code target} is allowed.
     *
     * @param target next state
     * @return {@code true} if allowed
     */
    public boolean canTransitionTo(RevisionState target) {
        return switch (this) {
            case DRAFT -> target == IN_REVIEW;
            case IN_REVIEW -> target == APPROVED || target == REJECTED || target == STALE;
            case APPROVED -> target == PUBLISHED || target == STALE;
            case PUBLISHED -> target == SUPERSEDED || target == DEPRECATED;
            case DEPRECATED -> target == SUPERSEDED || target == RETIRED;
            case SUPERSEDED -> target == PUBLISHED;
            case REJECTED, STALE, RETIRED -> false;
        };
    }
}
