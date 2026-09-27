package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.security.internal.GlobPattern;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One IdP → framework role mapping (a {@code dai_role_mapping} row or a static bootstrap rule from configuration).
 *
 * <p><b>Matching:</b> {@code matchValue} is compared with each candidate value of {@link #source()}. Without wildcards
 * it is an exact match. {@code *} (any sequence) and {@code ?} (one character) are supported, so
 * {@code sg-sales-*} is a prefix match; there is no escape character. {@link MappingSource#LDAP_GROUP} values are
 * compared case-insensitively after DN normalisation (whitespace around {@code ,} and {@code =} removed); all other
 * sources are case-sensitive. When {@code issuer} is set, the rule only applies to principals of that issuer.
 *
 * <p><b>Scope:</b> {@code PLATFORM_ADMIN}/{@code SECURITY_ADMIN} are global only; {@code AUDITOR} may be global or
 * per workspace; all other roles require a workspace (mirrors {@code ck_role_mapping_scope}).
 *
 * @param source      what is matched
 * @param issuer      optional issuer restriction
 * @param claimName   claim path for {@link MappingSource#OIDC_CLAIM} (dotted paths such as {@code realm_access.roles}
 *                    are supported), {@code null} otherwise
 * @param matchValue  exact value or glob
 * @param role        framework role granted on match
 * @param workspaceId workspace the role applies to, {@code null} for a global role
 * @param priority    ordering hint (lower first) used for explanations; matching rules are unioned
 */
public record RoleMappingRule(
        MappingSource source,
        @Nullable String issuer,
        @Nullable String claimName,
        String matchValue,
        FrameworkRole role,
        @Nullable UUID workspaceId,
        int priority) {

    /**
     * Validates the rule.
     */
    public RoleMappingRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(matchValue, "matchValue");
        Objects.requireNonNull(role, "role");
        if (matchValue.isBlank() || matchValue.length() > GlobPattern.MAX_LENGTH) {
            throw new IllegalArgumentException("matchValue must have 1.." + GlobPattern.MAX_LENGTH + " characters");
        }
        if ((source == MappingSource.OIDC_CLAIM) != (claimName != null && !claimName.isBlank())) {
            throw new IllegalArgumentException("claimName is required for OIDC_CLAIM mappings and forbidden otherwise");
        }
        if (role.globalOnly() && workspaceId != null) {
            throw new IllegalArgumentException(role + " is a global role and cannot be scoped to a workspace");
        }
        if (!role.globalOnly() && role != FrameworkRole.AUDITOR && workspaceId == null) {
            throw new IllegalArgumentException(role + " must be scoped to a workspace");
        }
    }

    /**
     * Bootstrap shorthand {@code match: { authority: … }}.
     *
     * @param authority   authority string or glob
     * @param role        role
     * @param workspaceId workspace, or {@code null} for global roles
     * @return the rule
     */
    public static RoleMappingRule authority(String authority, FrameworkRole role, @Nullable UUID workspaceId) {
        return new RoleMappingRule(MappingSource.AUTHORITY, null, null, authority, role, workspaceId, 100);
    }

    /**
     * Bootstrap shorthand {@code match: { group: … }} — a value of the configured groups claim.
     *
     * @param groupsClaim the groups claim name ({@link IdentityClaimSettings#groupsClaim()})
     * @param group       group value or glob
     * @param role        role
     * @param workspaceId workspace, or {@code null} for global roles
     * @return the rule
     */
    public static RoleMappingRule group(String groupsClaim, String group, FrameworkRole role, @Nullable UUID workspaceId) {
        return new RoleMappingRule(MappingSource.OIDC_CLAIM, null, groupsClaim, group, role, workspaceId, 100);
    }

    /**
     * Bootstrap shorthand {@code match: { ldapGroup: … }}.
     *
     * @param groupDn     group DN or glob
     * @param role        role
     * @param workspaceId workspace, or {@code null} for global roles
     * @return the rule
     */
    public static RoleMappingRule ldapGroup(String groupDn, FrameworkRole role, @Nullable UUID workspaceId) {
        return new RoleMappingRule(MappingSource.LDAP_GROUP, null, null, groupDn, role, workspaceId, 100);
    }

    /**
     * Bootstrap shorthand {@code match: { scope: … }}.
     *
     * @param scope       OAuth scope or glob
     * @param role        role
     * @param workspaceId workspace, or {@code null} for global roles
     * @return the rule
     */
    public static RoleMappingRule scope(String scope, FrameworkRole role, @Nullable UUID workspaceId) {
        return new RoleMappingRule(MappingSource.SCOPE, null, null, scope, role, workspaceId, 100);
    }
}
