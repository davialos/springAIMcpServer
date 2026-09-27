package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.security.internal.Reflection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fallback extractor for LDAP / Active Directory, {@code UserDetailsService}-backed and any other authentication.
 * Always returns a result, so it must be the last extractor.
 *
 * <ul>
 *   <li><b>LDAP</b> ({@code LdapUserDetails}, detected reflectively via a public {@code getDn()}): subject = normalised
 *       user DN, issuer = {@link IdentityClaimSettings#ldapIssuer()}, groups = DNs of {@code LdapAuthority}
 *       authorities ({@code getDn()}) plus authorities that look like DNs (e.g. {@code memberOf} values).</li>
 *   <li><b>Other</b>: subject = {@code Authentication#getName()}, issuer = {@link IdentityClaimSettings#localIssuer()};
 *       DN-looking authorities are still treated as LDAP groups.</li>
 * </ul>
 */
public final class UserDetailsIdentityExtractor implements IdentityExtractor {

    @Override
    public ExtractedIdentity extract(Authentication authentication, IdentityClaimSettings settings) {
        Object principal = authentication.getPrincipal();
        Set<String> authorities = ClaimValues.authorities(authentication);
        Set<String> ldapGroups = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (Reflection.invokeGetter(authority, "getDn") instanceof String dn && !dn.isBlank()) {
                ldapGroups.add(ClaimValues.normalizeDn(dn));
            }
        }
        for (String authority : authorities) {
            if (ClaimValues.looksLikeDn(authority)) {
                ldapGroups.add(ClaimValues.normalizeDn(authority));
            }
        }

        String displayName = principal instanceof UserDetails user ? user.getUsername() : authentication.getName();
        IdentityKind kind;
        String subject;
        String issuer;
        if (Reflection.invokeGetter(principal, "getDn") instanceof String userDn && !userDn.isBlank()) {
            kind = IdentityKind.LDAP;
            subject = ClaimValues.normalizeDn(userDn);
            issuer = settings.ldapIssuer();
        } else {
            kind = principal instanceof UserDetails ? IdentityKind.USER_DETAILS : IdentityKind.GENERIC;
            subject = authentication.getName();
            issuer = settings.localIssuer();
        }
        return new ExtractedIdentity(kind, issuer, subject, displayName, Map.of(), authorities, ldapGroups, Set.of(),
                null, null, null, SessionDigests.of(authentication));
    }
}
