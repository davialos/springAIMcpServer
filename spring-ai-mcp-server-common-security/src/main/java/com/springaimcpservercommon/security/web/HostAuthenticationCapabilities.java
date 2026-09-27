package com.springaimcpservercommon.security.web;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the host's Spring Security setup offers, detected by autoconfigure from the bean factory. Beans of optional
 * types are passed as {@code Object} so that this class loads without the OAuth2 modules; they are cast only inside
 * {@link ResourceServerSupport}, which is used only when those modules are present.
 *
 * @param jwtDecoder                   the host's {@code JwtDecoder} bean, if any
 * @param opaqueTokenIntrospector      the host's {@code OpaqueTokenIntrospector} bean, if any
 * @param oauth2Login                  whether a {@code ClientRegistrationRepository} exists (host uses oauth2Login)
 * @param loginRegistrationIds         client registration ids, if enumerable (used for the admin login redirect)
 * @param sessionLogin                 whether the host has another session login (form, LDAP, SAML …) our admin
 *                                     chain can rely on
 */
public record HostAuthenticationCapabilities(
        @Nullable Object jwtDecoder,
        @Nullable Object opaqueTokenIntrospector,
        boolean oauth2Login,
        List<String> loginRegistrationIds,
        boolean sessionLogin) {

    /**
     * Copies the list.
     */
    public HostAuthenticationCapabilities {
        loginRegistrationIds = List.copyOf(loginRegistrationIds);
    }

    /**
     * Whether a bearer-token mechanism (JWT or opaque) is available.
     *
     * @return {@code true} if the resource server can be enabled
     */
    public boolean bearerTokens() {
        return jwtDecoder != null || opaqueTokenIntrospector != null;
    }

    /**
     * Whether any host authentication mechanism exists.
     *
     * @return {@code true} if something can authenticate a human or a token
     */
    public boolean any() {
        return bearerTokens() || oauth2Login || sessionLogin;
    }

    /**
     * No host authentication at all.
     *
     * @return empty capabilities
     */
    public static HostAuthenticationCapabilities none() {
        return new HostAuthenticationCapabilities(null, null, false, List.of(), false);
    }
}
