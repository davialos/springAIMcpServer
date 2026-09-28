package com.springaimcpservercommon.ai.tool;

/**
 * Status codes for the tool result envelope (LLD-07 §3a).
 *
 * <p>Every tool result uses one of these status values so the model can self-correct:
 * it can distinguish "nothing matched" ({@link #EMPTY}) from "not allowed" ({@link #NOT_PERMITTED})
 * from a transient failure ({@link #UNAVAILABLE}).
 */
public enum ToolResultStatus {
    /** Execution succeeded and the result contains data. */
    OK,
    /** Execution succeeded but returned no rows / no output. */
    EMPTY,
    /** Result was truncated by the row cap or result-max-chars limit. */
    TRUNCATED,
    /** Execution failed with a safe, displayable error message. */
    ERROR,
    /** The calling principal lacks the permission to invoke this tool or access the data. */
    NOT_PERMITTED,
    /** The tool is temporarily unavailable (provider down, circuit open). */
    UNAVAILABLE,
    /** The tool created a reviewed change proposal (LLD-11); the proposal has not yet been applied. */
    PROPOSED
}
