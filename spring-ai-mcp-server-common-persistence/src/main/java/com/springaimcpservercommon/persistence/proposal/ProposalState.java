package com.springaimcpservercommon.persistence.proposal;

/**
 * States of a change proposal (LLD-11 §3, {@code ck_change_proposal_state}).
 *
 * <pre>
 * PROPOSED ─edit→ EDITED ─edit→ EDITED
 * PROPOSED|EDITED ─confirm(owner, hash)→ CONFIRMED            (SELF_CONFIRM)
 *                                      → AWAITING_APPROVAL    (SELF_CONFIRM_PLUS_APPROVER)
 * AWAITING_APPROVAL ─approve×n (≠ owner)→ CONFIRMED ; ─reject (≠ owner)→ REJECTED
 * PROPOSED|EDITED|AWAITING_APPROVAL ─decline (owner)→ REJECTED ; ─ttl→ EXPIRED
 * CONFIRMED ─→ APPLYING ─→ APPLIED | CONFLICT | FAILED ; CONFIRMED ─→ CONFLICT | FAILED
 * </pre>
 */
public enum ProposalState {
    /** Created by a tool or write endpoint; nothing written. */
    PROPOSED,
    /** Edited by the owner (new content hash). */
    EDITED,
    /** Confirmed by the owner, waiting for second-person approval. */
    AWAITING_APPROVAL,
    /** Confirmed (and approved if required); ready to apply. */
    CONFIRMED,
    /** Being applied through the host's write path. */
    APPLYING,
    /** Applied (terminal). */
    APPLIED,
    /** Declined by the owner or rejected by an approver (terminal). */
    REJECTED,
    /** Not confirmed/approved before its expiry (terminal). */
    EXPIRED,
    /** Target changed since the proposal was made (terminal). */
    CONFLICT,
    /** Apply failed; nothing committed (terminal). */
    FAILED;

    /**
     * Whether no further transition is possible.
     *
     * @return {@code true} for APPLIED, REJECTED, EXPIRED, CONFLICT and FAILED
     */
    public boolean isTerminal() {
        return switch (this) {
            case APPLIED, REJECTED, EXPIRED, CONFLICT, FAILED -> true;
            default -> false;
        };
    }

    /**
     * Whether the proposal still waits for its owner or approvers (and can expire).
     *
     * @return {@code true} for PROPOSED, EDITED and AWAITING_APPROVAL
     */
    public boolean isPending() {
        return this == PROPOSED || this == EDITED || this == AWAITING_APPROVAL;
    }
}
