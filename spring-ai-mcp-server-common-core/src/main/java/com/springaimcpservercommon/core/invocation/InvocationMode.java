package com.springaimcpservercommon.core.invocation;

/** What an invocation is allowed to do right now (ADR-0014). */
public enum InvocationMode {
    /** Ordinary request handling outside any AI tool call. */
    STANDARD,
    /** Inside an AI- or MCP-initiated read tool: any write is vetoed by the write guard. */
    AI_READ,
    /** Applying a confirmed change proposal on behalf of its owner (LLD-11 §2). */
    PROPOSAL_APPLY
}
