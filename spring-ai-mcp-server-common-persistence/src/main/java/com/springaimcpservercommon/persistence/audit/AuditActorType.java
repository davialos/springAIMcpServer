package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.principal.SubjectType;

/**
 * Kind of actor of an audit event ({@code ck_audit_event_actor_type}): the principal subject types plus SYSTEM, which
 * is the only type without an actor id.
 */
public enum AuditActorType {
    /** Human user. */
    USER,
    /** IdP group. */
    GROUP,
    /** Framework service account. */
    SERVICE_ACCOUNT,
    /** MCP client application. */
    MCP_CLIENT,
    /** The framework itself (no actor id). */
    SYSTEM;

    /**
     * Maps a principal subject type.
     *
     * @param type subject type
     * @return the matching actor type
     */
    public static AuditActorType of(SubjectType type) {
        return switch (type) {
            case USER -> USER;
            case GROUP -> GROUP;
            case SERVICE_ACCOUNT -> SERVICE_ACCOUNT;
            case MCP_CLIENT -> MCP_CLIENT;
        };
    }
}
