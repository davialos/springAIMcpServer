package com.springaimcpservercommon.core.invocation;

/** Entry channel of an invocation (matches the {@code channel} columns of the store). */
public enum Channel {
    /** Agent chat API (sync or SSE). */
    CHAT,
    /** Authoring playground (draft revisions). */
    PLAYGROUND,
    /** MCP server. */
    MCP,
    /** Dynamic REST endpoint. */
    ENDPOINT
}
