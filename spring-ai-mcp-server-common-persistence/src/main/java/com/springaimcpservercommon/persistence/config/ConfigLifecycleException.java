package com.springaimcpservercommon.persistence.config;

import java.io.Serial;

/**
 * A configuration lifecycle rule was violated: invalid revision state transition, edit of a non-draft revision,
 * publish of a retired resource, dangling dependency in the live set, unknown rollback generation. Maps to HTTP 409
 * in the admin API.
 */
public class ConfigLifecycleException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was violated (no spec content)
     */
    public ConfigLifecycleException(String message) {
        super(message);
    }
}
