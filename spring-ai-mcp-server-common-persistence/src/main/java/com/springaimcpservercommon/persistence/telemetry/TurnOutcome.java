package com.springaimcpservercommon.persistence.telemetry;

/** Outcome of an agent turn ({@code ck_agent_turn_outcome}); every outcome but SUCCESS needs an error code. */
public enum TurnOutcome {
    /** Answered. */
    SUCCESS,
    /** Failed. */
    FAILED,
    /** Cancelled. */
    CANCELLED,
    /** Rejected before running (authorization, guardrail, budget, kill switch). */
    REJECTED
}
