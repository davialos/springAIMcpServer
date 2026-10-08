package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Who is calling and the scope everything runs in, read from the verified access token and nowhere else: a request body
 * or query string can never name a tenant or organization.
 *
 * @param userId           user id
 * @param username         login name
 * @param displayName      display name
 * @param admin            whether the user is an ADMIN (reads logs, may share rules with the whole tenant)
 * @param tenantId         tenant
 * @param tenantName       tenant display name
 * @param organizationId   organization, or {@code null} for a tenant-wide user
 * @param organizationName organization display name, or {@code null}
 */
public record Caller(UUID userId, String username, String displayName, boolean admin, UUID tenantId,
                     String tenantName, @Nullable UUID organizationId, @Nullable String organizationName) {

    /**
     * Builds the caller from a verified token.
     *
     * @param jwt the token
     * @return the caller
     * @throws IllegalArgumentException when a mandatory claim is missing or malformed (treated as unauthenticated)
     */
    public static Caller from(Jwt jwt) {
        String org = jwt.getClaimAsString(TokenClaims.ORGANIZATION_ID);
        return new Caller(uuid(jwt.getSubject()), string(jwt, TokenClaims.USERNAME),
                string(jwt, TokenClaims.DISPLAY_NAME), "ADMIN".equals(jwt.getClaimAsString(TokenClaims.ROLE)),
                uuid(jwt.getClaimAsString(TokenClaims.TENANT_ID)), string(jwt, TokenClaims.TENANT_NAME),
                org == null || org.isBlank() ? null : uuid(org), jwt.getClaimAsString(TokenClaims.ORGANIZATION_NAME));
    }

    /**
     * The caller of the code running now, from the request's verified token. Used by code that is not a controller (the
     * AI assistant's tools run on the caller's behalf, with the caller's authentication, ADR-0028).
     *
     * @return the caller
     * @throws IllegalStateException when no token-authenticated caller is present
     */
    public static Caller current() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken t) {
            return from(t.getToken());
        }
        throw new IllegalStateException("no token-authenticated caller");
    }

    private static UUID uuid(@Nullable String value) {
        if (value == null) {
            throw new IllegalArgumentException("token is missing a required id claim");
        }
        return UUID.fromString(value);
    }

    private static String string(Jwt jwt, String claim) {
        String v = jwt.getClaimAsString(claim);
        return v == null ? "" : v;
    }

    /** The role as stored in the audit log. */
    public String role() {
        return admin ? "ADMIN" : "USER";
    }
}
