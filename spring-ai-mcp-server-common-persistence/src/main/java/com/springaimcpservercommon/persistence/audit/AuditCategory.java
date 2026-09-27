package com.springaimcpservercommon.persistence.audit;

/** Audit event category ({@code ck_audit_event_category}). */
public enum AuditCategory {
    /** Control-plane administration. */
    ADMIN,
    /** Security-relevant events (never sampled). */
    SECURITY,
    /** Data-plane invocations (may be sampled). */
    INVOCATION,
    /** Reviewed writes (proposal decision trail). */
    DATA_WRITE,
    /** System events. */
    SYSTEM
}
