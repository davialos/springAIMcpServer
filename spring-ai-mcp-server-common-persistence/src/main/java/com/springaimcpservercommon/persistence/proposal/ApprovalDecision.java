package com.springaimcpservercommon.persistence.proposal;

/** Decision of a second-person approver ({@code ck_change_proposal_approval_decision}). */
public enum ApprovalDecision {
    /** Approved. */
    APPROVED,
    /** Rejected (a comment is mandatory). */
    REJECTED
}
