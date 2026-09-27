package com.springaimcpservercommon.persistence.config;

import java.io.Serial;

/**
 * Four-eyes rule violated: the author of a revision tried to review it (SEC-01 §6, LLD-09 §2). Enforced in Java
 * before the insert; the {@code trg_review_sod} trigger is defence in depth. Maps to HTTP 403 in the admin API.
 */
public class SegregationOfDutiesException extends ConfigLifecycleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was violated
     */
    public SegregationOfDutiesException(String message) {
        super(message);
    }
}
