package com.springaimcpservercommon.security.principal;

/**
 * Shape of the host authentication an identity was extracted from (SEC-01 §2).
 */
public enum IdentityKind {
    /** Resource server with a JWT bearer token ({@code JwtAuthenticationToken}). */
    JWT_BEARER,
    /** Resource server with an introspected opaque token ({@code BearerTokenAuthentication}). */
    OPAQUE_TOKEN,
    /** OpenID Connect login session ({@code OidcUser}). */
    OIDC_LOGIN,
    /** Plain OAuth 2.0 login session without an ID token ({@code OAuth2User}). */
    OAUTH2_LOGIN,
    /** SAML 2.0 login ({@code Saml2Authentication}). */
    SAML2,
    /** LDAP / Active Directory bind ({@code LdapUserDetails}). */
    LDAP,
    /** {@code UserDetailsService}-backed form or basic login. */
    USER_DETAILS,
    /** Any other authentication (header-based gateway auth, custom providers). */
    GENERIC,
    /** Our API key authentication of a service account. */
    API_KEY
}
