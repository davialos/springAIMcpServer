package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The matching part of a role mapping (SEC-01 §3): "when the host authentication carries {@code matchValue} in
 * {@code source} (optionally from {@code issuer}/{@code claimName}), grant {@code role} (in {@code workspaceId})".
 *
 * <p>The constructor enforces the same rules as the {@code ck_role_mapping_claim} and {@code ck_role_mapping_scope}
 * constraints: a claim name is required exactly for {@link RoleMappingSource#OIDC_CLAIM}; global-only roles must not
 * name a workspace; {@link FrameworkRole#AUDITOR} may be global or workspace-scoped; every other role needs one.
 *
 * @param source      where to match
 * @param issuer      optional issuer restriction ({@code null} = any issuer)
 * @param claimName   claim to read, only for {@code OIDC_CLAIM}
 * @param matchValue  exact value to match
 * @param role        framework role granted
 * @param workspaceId workspace for workspace-scoped roles
 * @param priority    evaluation order (lower first)
 */
public record RoleMappingRule(
        RoleMappingSource source,
        @Nullable String issuer,
        @Nullable String claimName,
        String matchValue,
        FrameworkRole role,
        @Nullable UUID workspaceId,
        int priority) {

    /** Default priority of a mapping. */
    public static final int DEFAULT_PRIORITY = 100;

    /** Validates the rule. */
    public RoleMappingRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(matchValue, "matchValue");
        Objects.requireNonNull(role, "role");
        if (matchValue.isBlank()) {
            throw new IllegalArgumentException("matchValue must not be blank");
        }
        if ((source == RoleMappingSource.OIDC_CLAIM) != (claimName != null)) {
            throw new IllegalArgumentException("claimName is required for OIDC_CLAIM mappings and forbidden otherwise");
        }
        if (claimName != null && claimName.isBlank()) {
            throw new IllegalArgumentException("claimName must not be blank");
        }
        if (role.globalOnly() && workspaceId != null) {
            throw new IllegalArgumentException(role + " is global and cannot be scoped to a workspace");
        }
        if (!role.globalOnly() && role != FrameworkRole.AUDITOR && workspaceId == null) {
            throw new IllegalArgumentException(role + " must be scoped to a workspace");
        }
    }
}
