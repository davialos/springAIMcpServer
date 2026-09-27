package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.core.hash.Sha256;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Digest of the HTTP session an authentication belongs to, used only as part of the mapping-cache key so that a new
 * login gets a fresh mapping. The raw session id is never stored or logged.
 */
final class SessionDigests {

    private SessionDigests() {
    }

    static @Nullable String of(Authentication authentication) {
        if (authentication.getDetails() instanceof WebAuthenticationDetails details && details.getSessionId() != null) {
            return Sha256.of("session:" + details.getSessionId());
        }
        return null;
    }
}
