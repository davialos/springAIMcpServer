package com.springaimcpservercommon.persistence.audit;

/** Kind of evidence content ({@code ck_audit_evidence_content_type}). */
public enum EvidenceContentType {
    /** Prompt sent to the model (post-redaction). */
    PROMPT,
    /** Model output. */
    COMPLETION,
    /** Tool arguments. */
    TOOL_ARGUMENTS,
    /** Rows or result returned by a tool. */
    TOOL_RESULT,
    /** Full payload of a change proposal. */
    PROPOSAL_PAYLOAD
}
