package com.springaimcpservercommon.validation;

/**
 * Per-call options.
 *
 * @param failFast stop at the first error instead of collecting them all
 */
public record ValidationOptions(boolean failFast) {

    /** Collect every violation. */
    public static final ValidationOptions COLLECT_ALL = new ValidationOptions(false);

    /** Stop at the first error. */
    public static final ValidationOptions FAIL_FAST = new ValidationOptions(true);
}
