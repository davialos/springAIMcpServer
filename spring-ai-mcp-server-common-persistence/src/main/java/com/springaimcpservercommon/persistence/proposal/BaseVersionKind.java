package com.springaimcpservercommon.persistence.proposal;

/** Kind of the base version token of a record change ({@code ck_change_proposal_record_version_kind}, LLD-11 §5). */
public enum BaseVersionKind {
    /** JPA {@code @Version} value. */
    JPA_VERSION,
    /** Hibernate Envers revision number. */
    ENVERS_REVISION,
    /** Version column of a configured history table. */
    HISTORY_TABLE,
    /** System-versioned temporal table period start. */
    TEMPORAL,
    /** Hash of the exposed attributes (fallback). */
    ROW_HASH,
    /** Host-specific token. */
    CUSTOM
}
