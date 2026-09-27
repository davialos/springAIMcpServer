package com.springaimcpservercommon.security.principal;

import java.util.Map;
import java.util.Set;

/**
 * SPI: resolves a user's groups when the token cannot carry them, typically Entra ID group overage (a user in more
 * than ~200 groups gets {@code _claim_names.groups} instead of a {@code groups} claim). A host implementation may call
 * Microsoft Graph with its own credentials; the framework never forwards the caller's token (no token passthrough).
 *
 * <p>Called only on principal-mapping cache misses. Implementations must apply their own timeout; exceptions are
 * logged and treated as "no groups" (fail closed: fewer groups means fewer permissions).
 */
@FunctionalInterface
public interface GroupResolver {

    /**
     * Resolves group ids.
     *
     * @param request the subject whose groups are needed
     * @return group ids in the same form the groups claim would have (e.g. Entra object ids)
     */
    Set<String> resolveGroups(Request request);

    /**
     * Input of a group resolution.
     *
     * @param issuer    principal issuer
     * @param subjectId stable subject id
     * @param claims    the token claims (for tenant id etc.); must not be logged
     */
    record Request(String issuer, String subjectId, Map<String, Object> claims) {
        /**
         * Copies the claims.
         */
        public Request {
            claims = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(claims));
        }
    }
}
