package com.springaimcpservercommon.persistence.telemetry;

/** Why an MCP session ended ({@code ck_mcp_session_end_reason}). */
public enum McpSessionEndReason {
    /** The client closed it. */
    CLIENT_CLOSED,
    /** Idle timeout. */
    IDLE_TIMEOUT,
    /** The access token expired. */
    TOKEN_EXPIRED,
    /** Consent or client revoked. */
    REVOKED,
    /** Server shut down. */
    SERVER_SHUTDOWN,
    /** Ended by an error. */
    ERROR
}
