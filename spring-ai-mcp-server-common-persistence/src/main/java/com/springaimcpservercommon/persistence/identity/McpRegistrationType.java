package com.springaimcpservercommon.persistence.identity;

/** How an MCP client became known (matches {@code ck_mcp_client_registration}, LLD-07 §5.3). */
public enum McpRegistrationType {
    /** Registered by an administrator in the host's IdP. */
    PRE_REGISTERED,
    /** Client ID Metadata Document. */
    CIMD,
    /** OAuth Dynamic Client Registration. */
    DCR
}
