package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.security.internal.Reflection;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Extracts identities from OAuth 2.0 / OIDC authentications: resource-server JWT ({@code JwtAuthenticationToken},
 * principal {@code Jwt}), opaque-token introspection ({@code BearerTokenAuthentication}), OIDC login ({@code OidcUser})
 * and plain OAuth 2.0 login ({@code OAuth2User}).
 *
 * <p>Touches only {@code spring-security-oauth2-core} types. Instantiate it only when that module is present
 * ({@link IdentityExtraction#defaults()} checks with {@code ClassUtils.isPresent}).
 */
public final class OAuth2IdentityExtractor implements IdentityExtractor {

    /** Marker class checked before this extractor is instantiated. */
    public static final String REQUIRED_CLASS = "org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal";

    private static final String[] CLIENT_ID_CLAIMS = {"azp", "client_id", "appid"};

    @Override
    public @Nullable ExtractedIdentity extract(Authentication authentication, IdentityClaimSettings settings) {
        Object principal = authentication.getPrincipal();
        Object credentials = authentication.getCredentials();
        Map<String, Object> claims;
        IdentityKind kind;
        AbstractOAuth2Token token = null;
        if (principal instanceof OidcUser oidcUser) {
            claims = oidcUser.getClaims();
            kind = IdentityKind.OIDC_LOGIN;
            token = oidcUser.getIdToken();
        } else if (principal instanceof OAuth2AuthenticatedPrincipal p && credentials instanceof AbstractOAuth2Token t) {
            claims = p.getAttributes();
            kind = IdentityKind.OPAQUE_TOKEN;
            token = t;
        } else if (principal instanceof ClaimAccessor accessor && credentials instanceof AbstractOAuth2Token t) {
            claims = accessor.getClaims();
            kind = IdentityKind.JWT_BEARER;
            token = t;
        } else if (principal instanceof OAuth2AuthenticatedPrincipal p) {
            claims = p.getAttributes();
            kind = IdentityKind.OAUTH2_LOGIN;
        } else {
            return null;
        }

        String issuer = ClaimValues.string(claims.get("iss"));
        if (issuer == null && kind == IdentityKind.OAUTH2_LOGIN) {
            Object registrationId = Reflection.invokeGetter(authentication, "getAuthorizedClientRegistrationId");
            issuer = registrationId == null ? null : "oauth2:" + registrationId;
        }
        String subject = ClaimValues.string(ClaimValues.lookup(claims, settings.subjectClaim()));
        if (subject == null) {
            subject = ClaimValues.string(claims.get("sub"));
        }
        if (subject == null) {
            subject = authentication.getName();
        }

        Set<String> authorities = ClaimValues.authorities(authentication);
        Set<String> scopes = new LinkedHashSet<>(ClaimValues.scopes(claims.get("scope")));
        scopes.addAll(ClaimValues.scopes(claims.get("scp")));
        scopes.addAll(ClaimValues.scopesFromAuthorities(authorities));

        String clientId = null;
        for (String claim : CLIENT_ID_CLAIMS) {
            clientId = ClaimValues.string(claims.get(claim));
            if (clientId != null) {
                break;
            }
        }

        Instant authenticatedAt = ClaimValues.instant(claims.get("auth_time"));
        if (authenticatedAt == null) {
            authenticatedAt = ClaimValues.instant(claims.get("iat"));
        }
        Instant expiresAt = token == null ? null : token.getExpiresAt();
        String digest = token == null ? null : Sha256.of(token.getTokenValue());

        return new ExtractedIdentity(kind, issuer, subject,
                ClaimValues.string(ClaimValues.lookup(claims, settings.displayNameClaim())),
                claims, authorities, Set.of(), scopes, clientId, authenticatedAt, expiresAt, digest);
    }
}
