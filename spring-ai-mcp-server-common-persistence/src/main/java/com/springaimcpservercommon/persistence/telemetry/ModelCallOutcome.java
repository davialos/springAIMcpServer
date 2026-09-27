package com.springaimcpservercommon.persistence.telemetry;

/** Outcome of a model call ({@code ck_model_call_outcome}). */
public enum ModelCallOutcome {
    /** Completed. */
    SUCCESS,
    /** Provider or client error. */
    ERROR,
    /** Timed out. */
    TIMEOUT,
    /** Cancelled. */
    CANCELLED,
    /** Not attempted: circuit breaker open. */
    CIRCUIT_OPEN,
    /** Rejected by a rate limit (ours or the provider's). */
    RATE_LIMITED
}
