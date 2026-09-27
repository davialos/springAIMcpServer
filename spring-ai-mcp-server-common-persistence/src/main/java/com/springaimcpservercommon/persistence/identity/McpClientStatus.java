package com.springaimcpservercommon.persistence.identity;

/** Approval status of an MCP client in a workspace (matches {@code ck_mcp_client_status}). */
public enum McpClientStatus {
    /** Known but not usable until approved. */
    PENDING,
    /** Usable by users who consent. */
    APPROVED,
    /** Blocked; all consents are revoked with it. Terminal. */
    REVOKED
}
