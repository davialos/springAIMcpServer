package com.springaimcpservercommon.persistence.audit;

/** Plane an audit event belongs to ({@code ck_audit_event_plane}). */
public enum AuditPlane {
    /** Admin control plane. */
    CONTROL,
    /** Dynamic endpoints and queries. */
    DATA,
    /** Agent runtime. */
    AGENT,
    /** MCP server. */
    MCP,
    /** Background system work. */
    SYSTEM
}
