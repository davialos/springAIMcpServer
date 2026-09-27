package com.springaimcpservercommon.persistence.proposal;

/**
 * Approval policy of a proposal ({@code ck_change_proposal_approval}): SELF_CONFIRM needs 0 approvals,
 * SELF_CONFIRM_PLUS_APPROVER needs 1–5 approvals by principals other than the owner.
 */
public enum ApprovalRequirement {
    /** The owner's confirmation suffices. */
    SELF_CONFIRM,
    /** The owner confirms, then one or more other principals approve (four-eyes). */
    SELF_CONFIRM_PLUS_APPROVER
}
