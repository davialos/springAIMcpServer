package com.springaimcpservercommon.persistence.telemetry;

/** Why an agent turn ended ({@code ck_agent_turn_finish}). */
public enum TurnFinishReason {
    /** The model finished its answer. */
    STOP,
    /** Output token limit reached. */
    LENGTH,
    /** Maximum tool iterations reached. */
    TOOL_LIMIT,
    /** A budget stopped the turn. */
    BUDGET,
    /** Cancelled by the client or a deadline. */
    CANCELLED,
    /** Ended by an error. */
    ERROR
}
