package com.springaimcpservercommon.persistence.telemetry;

/** MCP transport of a session ({@code ck_mcp_session_transport}). */
public enum McpTransport {
    /** Stateful Streamable HTTP. */
    STREAMABLE_HTTP,
    /** Stateless Streamable HTTP. */
    STATELESS,
    /** Legacy HTTP+SSE. */
    SSE
}
