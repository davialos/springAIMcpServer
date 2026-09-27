package com.springaimcpservercommon.core.principal;

/** Kind of subject a principal refers to (matches {@code dai_principal.subject_type}). */
public enum SubjectType {
    /** A human user authenticated by the host's IdP. */
    USER,
    /** An IdP group (used in memberships and grants). */
    GROUP,
    /** A framework service account authenticated by API key or client credentials. */
    SERVICE_ACCOUNT,
    /** An MCP client application acting for a user. */
    MCP_CLIENT
}
