package com.springaimcpservercommon.security.principal;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Raw identity facts read from one host {@code Authentication}, before role mapping. Contains no credentials: a token
 * or session is represented only by a SHA-256 digest used as cache key.
 *
 * @param kind             authentication shape
 * @param issuer           issuer from the token, or {@code null} when the authentication has none
 * @param subject          raw subject (before e-mail pseudonymisation)
 * @param displayName      display name, if any
 * @param claims           token claims / user attributes / SAML attributes (never logged)
 * @param authorities      granted authority strings
 * @param ldapGroups       normalised LDAP group DNs
 * @param scopes           OAuth scopes of the current token
 * @param clientId         OAuth client id ({@code azp}/{@code client_id}/{@code appid}), if any
 * @param authenticatedAt  authentication time, if known
 * @param expiresAt        token expiry, if any
 * @param credentialDigest {@code sha256:} digest of the token value or session id, if any
 */
public record ExtractedIdentity(
        IdentityKind kind,
        @Nullable String issuer,
        String subject,
        @Nullable String displayName,
        Map<String, Object> claims,
        Set<String> authorities,
        Set<String> ldapGroups,
        Set<String> scopes,
        @Nullable String clientId,
        @Nullable Instant authenticatedAt,
        @Nullable Instant expiresAt,
        @Nullable String credentialDigest) {

    /**
     * Validates and copies.
     */
    public ExtractedIdentity {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        authorities = Set.copyOf(authorities);
        ldapGroups = Set.copyOf(ldapGroups);
        scopes = Set.copyOf(scopes);
    }
}
