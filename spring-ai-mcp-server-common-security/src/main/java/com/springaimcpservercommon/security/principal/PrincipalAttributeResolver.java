package com.springaimcpservercommon.security.principal;

import org.springframework.security.core.Authentication;

import java.util.Map;

/**
 * SPI: contributes ABAC attributes that are not in the token, e.g. {@code tenantId} or {@code region} from an HR
 * system (SEC-01 §3). Resolvers run on principal-mapping cache misses only, so results are cached with the principal.
 *
 * <p>Values must be simple ({@code String}, {@code Boolean}, {@code Number}) or lists of those; other values are
 * ignored. A resolver's value replaces a claim-derived attribute of the same name. Exceptions are logged and the
 * resolver's contribution is skipped (fewer attributes can only make ABAC conditions fail, never pass).
 */
@FunctionalInterface
public interface PrincipalAttributeResolver {

    /**
     * Resolves attributes for an authenticated caller.
     *
     * @param authentication the host authentication
     * @return attribute name → value (never {@code null})
     */
    Map<String, Object> resolve(Authentication authentication);
}
