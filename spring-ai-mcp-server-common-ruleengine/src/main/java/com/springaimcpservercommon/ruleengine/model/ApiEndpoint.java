package com.springaimcpservercommon.ruleengine.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An HTTP endpoint a channel calls.
 *
 * @param id                 endpoint id
 * @param name               display name
 * @param method             HTTP method
 * @param url                absolute http(s) URL
 * @param environment        declared environment
 * @param timeoutMillis      per-call timeout
 * @param authSecretRef      reference the host resolves to a credential (never the secret itself)
 * @param externalConfirmedBy who confirmed an EXTERNAL endpoint, or {@code null}
 * @param externalConfirmedAt when it was confirmed, or {@code null}
 */
public record ApiEndpoint(UUID id, String name, String method, String url, ApiEnvironment environment,
                          int timeoutMillis, @Nullable String authSecretRef, @Nullable String externalConfirmedBy,
                          @Nullable Instant externalConfirmedAt) {

    /**
     * Whether an EXTERNAL endpoint carries a recorded confirmation.
     *
     * @return {@code true} when confirmed
     */
    public boolean externalConfirmed() {
        return externalConfirmedBy != null && externalConfirmedAt != null;
    }
}
