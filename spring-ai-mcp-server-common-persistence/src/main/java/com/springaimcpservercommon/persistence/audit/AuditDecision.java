package com.springaimcpservercommon.persistence.audit;

/** Authorization decision recorded with an audit event ({@code ck_audit_event_decision}); DENY needs a reason. */
public enum AuditDecision {
    /** Permitted. */
    PERMIT,
    /** Denied. */
    DENY,
    /** No authorization decision involved. */
    NOT_APPLICABLE
}
