package com.springaimcpservercommon.persistence.maintenance;

/** Outcome of a maintenance job run ({@code ck_job_run_outcome}). */
public enum JobOutcome {
    /** Every step succeeded. */
    SUCCESS,
    /** Some steps failed. */
    PARTIAL,
    /** Every step failed. */
    FAILED,
    /** Not run: another node holds the job's advisory lock. */
    SKIPPED
}
