package com.springaimcpservercommon.persistence.telemetry;

/** Status of an MCP JSON-RPC request ({@code ck_mcp_request_status}). */
public enum McpRequestStatus {
    /** Answered. */
    OK,
    /** Failed. */
    ERROR,
    /** Not authorised. */
    DENIED,
    /** Rate limited. */
    RATE_LIMITED
}
