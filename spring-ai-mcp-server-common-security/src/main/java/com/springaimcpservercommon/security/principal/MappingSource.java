package com.springaimcpservercommon.security.principal;

/**
 * What a role mapping matches against (values of {@code dai_role_mapping.source}).
 */
public enum MappingSource {
    /** A Spring Security {@code GrantedAuthority} string (e.g. {@code ROLE_PLATFORM_ADMIN}); case-sensitive. */
    AUTHORITY,
    /** A value of a token/user claim named by {@code claimName} (e.g. {@code groups}, {@code roles}); case-sensitive. */
    OIDC_CLAIM,
    /** An LDAP group distinguished name; case-insensitive after DN normalisation. */
    LDAP_GROUP,
    /** An OAuth scope of the current token (e.g. {@code dai.agents.invoke}); case-sensitive. */
    SCOPE
}
