package com.springaimcpservercommon.persistence.proposal;

/** Kind of change ({@code ck_change_proposal_change_kind}). */
public enum ChangeKind {
    /** Creates one record. */
    CREATE,
    /** Updates one record. */
    UPDATE,
    /** Deletes one record. */
    DELETE,
    /** Several record changes applied all-or-nothing. */
    BULK
}
