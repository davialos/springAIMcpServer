package com.springaimcpservercommon.persistence.identity;

/** Where a role mapping matches in the host's authentication (matches {@code ck_role_mapping_source}). */
public enum RoleMappingSource {
    /** A Spring Security {@code GrantedAuthority} string. */
    AUTHORITY,
    /** A value of a named OIDC/JWT claim ({@code claimName} required). */
    OIDC_CLAIM,
    /** An LDAP group DN. */
    LDAP_GROUP,
    /** An OAuth scope. */
    SCOPE
}
