package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.security.internal.Reflection;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extracts identities from SAML 2.0 logins without a compile dependency on {@code spring-security-saml2-service-provider}:
 * attributes are read reflectively from {@code Saml2AssertionAuthentication#getCredentials()} (a
 * {@code Saml2ResponseAssertionAccessor}, Spring Security 7) or from the deprecated {@code Saml2AuthenticatedPrincipal}.
 *
 * <p>Subject: the attribute named by {@link IdentityClaimSettings#subjectClaim()} if present (e.g. the Entra object id
 * attribute URI), otherwise the NameID. Issuer: {@code saml:<relyingPartyRegistrationId>}.
 */
public final class Saml2IdentityExtractor implements IdentityExtractor {

    /** Base class of every SAML 2.0 authentication. */
    public static final String SAML2_AUTHENTICATION = "org.springframework.security.saml2.provider.service.authentication.Saml2Authentication";

    @Override
    public @Nullable ExtractedIdentity extract(Authentication authentication, IdentityClaimSettings settings) {
        if (!Reflection.isInstanceOf(authentication, SAML2_AUTHENTICATION)) {
            return null;
        }
        Object credentials = authentication.getCredentials();
        Object principal = authentication.getPrincipal();

        Map<String, Object> claims = new LinkedHashMap<>();
        Object attributes = Reflection.invokeGetter(credentials, "getAttributes");
        if (!(attributes instanceof Map<?, ?>)) {
            attributes = Reflection.invokeGetter(principal, "getAttributes");
        }
        if (attributes instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    claims.put(k.toString(), v);
                }
            });
        }

        String subject = ClaimValues.string(ClaimValues.lookup(claims, settings.subjectClaim()));
        if (subject == null) {
            subject = ClaimValues.string(Reflection.invokeGetter(credentials, "getNameId"));
        }
        if (subject == null) {
            subject = authentication.getName();
        }

        Object registrationId = Reflection.invokeGetter(authentication, "getRelyingPartyRegistrationId");
        if (registrationId == null) {
            registrationId = Reflection.invokeGetter(principal, "getRelyingPartyRegistrationId");
        }
        String issuer = registrationId == null ? "saml" : "saml:" + registrationId;

        Set<String> authorities = ClaimValues.authorities(authentication);
        return new ExtractedIdentity(IdentityKind.SAML2, issuer, subject,
                ClaimValues.string(ClaimValues.lookup(claims, settings.displayNameClaim())),
                claims, authorities, Set.of(), Set.of(), null, null, null,
                SessionDigests.of(authentication));
    }
}
