package com.springaimcpservercommon.ruleengine.channel;

/**
 * Verdict of the API environment guard.
 *
 * @param verdict   what to do
 * @param reasonKey stable key a UI maps to its own text
 * @param message   English explanation, suitable for the confirmation pop-up
 */
public record ApiCheck(Verdict verdict, String reasonKey, String message) {

    /** The guard's decision. */
    public enum Verdict {
        /** The endpoint may be used. */
        ALLOWED,
        /** An EXTERNAL endpoint: show the pop-up and record the confirmation before using it. */
        CONFIRMATION_REQUIRED,
        /** The endpoint belongs to another environment (e.g. a DEV API in production). */
        REJECTED_ENVIRONMENT_MISMATCH
    }

    /**
     * Whether the endpoint may be called now.
     *
     * @return {@code true} for ALLOWED
     */
    public boolean allowed() {
        return verdict == Verdict.ALLOWED;
    }
}
