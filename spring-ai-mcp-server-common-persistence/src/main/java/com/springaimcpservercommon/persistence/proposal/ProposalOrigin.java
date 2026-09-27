package com.springaimcpservercommon.persistence.proposal;

/** Where a proposal came from ({@code ck_change_proposal_origin}). */
public enum ProposalOrigin {
    /** A mutating tool called by an agent. */
    AGENT_TOOL,
    /** A mutating tool called over MCP. */
    MCP_TOOL,
    /** A dynamic write endpoint. */
    WRITE_ENDPOINT
}
