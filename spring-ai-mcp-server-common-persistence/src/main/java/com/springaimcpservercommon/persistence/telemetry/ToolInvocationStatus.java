package com.springaimcpservercommon.persistence.telemetry;

/**
 * Result status of a tool invocation ({@code ck_tool_invocation_status}); PROPOSED requires a proposal id and access
 * mode PROPOSE.
 */
public enum ToolInvocationStatus {
    /** Returned data. */
    OK,
    /** Returned no rows. */
    EMPTY,
    /** Result truncated to limits. */
    TRUNCATED,
    /** Failed. */
    ERROR,
    /** Caller not permitted. */
    NOT_PERMITTED,
    /** Target unavailable (probe, kill switch, circuit). */
    UNAVAILABLE,
    /** A change proposal was created. */
    PROPOSED,
    /** Timed out. */
    TIMEOUT
}
