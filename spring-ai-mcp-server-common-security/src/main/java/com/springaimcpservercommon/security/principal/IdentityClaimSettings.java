package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * How claims and attributes of the host's authentication are read (SEC-01 §3,
 * {@code dynamic.ai.agent.security.identity.*}). Claim names may be dotted paths into nested claims
 * ({@code realm_access.roles} for Keycloak); a claim whose literal name contains dots (SAML attribute URIs) is found
 * first by exact name.
 *
 * @param issuerOverride        issuer to record instead of the token's {@code iss} (e.g. to merge several Entra
 *                              tenants), {@code null} to use the token issuer
 * @param subjectClaim          claim holding the stable subject id: {@code sub} (default) or {@code oid} for Entra ID
 * @param groupsClaim           claim / SAML attribute holding group ids (becomes {@code externalGroups})
 * @param rolesClaim            claim holding IdP app roles (usable in {@code OIDC_CLAIM} mappings), e.g. {@code roles}
 *                              or {@code realm_access.roles}
 * @param displayNameClaim      claim used as display name
 * @param clearanceClaim        claim holding a {@link Classification} name, {@code null} to always use the default
 * @param attributeClaims       ABAC attribute name → claim, e.g. {@code tenantId → tid}
 * @param localIssuer           issuer recorded for non-OAuth authentications (form/basic/header/custom)
 * @param ldapIssuer            issuer recorded for LDAP / Active Directory principals
 * @param detectGroupOverage    whether to detect Entra ID group overage ({@code _claim_names.groups} /
 *                              {@code hasgroups}) and call the {@link GroupResolver}
 * @param defaultClearance      clearance when no clearance claim is present or it is invalid
 * @param cacheTtl              lifetime of a cached mapping result (at most 5 minutes, SEC-01 §3)
 * @param cacheMaxEntries       bound of the mapping cache
 */
public record IdentityClaimSettings(
        @Nullable String issuerOverride,
        String subjectClaim,
        String groupsClaim,
        String rolesClaim,
        String displayNameClaim,
        @Nullable String clearanceClaim,
        Map<String, String> attributeClaims,
        String localIssuer,
        String ldapIssuer,
        boolean detectGroupOverage,
        Classification defaultClearance,
        Duration cacheTtl,
        int cacheMaxEntries) {

    /** Upper bound of {@link #cacheTtl()} mandated by SEC-01 §3. */
    public static final Duration MAX_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Validates the settings.
     */
    public IdentityClaimSettings {
        requireText(subjectClaim, "subjectClaim");
        requireText(groupsClaim, "groupsClaim");
        requireText(rolesClaim, "rolesClaim");
        requireText(displayNameClaim, "displayNameClaim");
        requireText(localIssuer, "localIssuer");
        requireText(ldapIssuer, "ldapIssuer");
        attributeClaims = Map.copyOf(attributeClaims);
        Objects.requireNonNull(defaultClearance, "defaultClearance");
        if (defaultClearance == Classification.INHERIT) {
            throw new IllegalArgumentException("defaultClearance must be a concrete classification");
        }
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheTtl.compareTo(MAX_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("cacheTtl must be in (0, 5m]");
        }
        if (cacheMaxEntries < 1) {
            throw new IllegalArgumentException("cacheMaxEntries must be >= 1");
        }
    }

    /**
     * Defaults: {@code sub}, {@code groups}, {@code roles}, {@code name}, no clearance claim, clearance INTERNAL,
     * 2 minute cache of 10 000 entries.
     *
     * @return default settings
     */
    public static IdentityClaimSettings defaults() {
        return new IdentityClaimSettings(null, "sub", "groups", "roles", "name", null, Map.of(), "host", "ldap:host",
                true, Classification.INTERNAL, Duration.ofMinutes(2), 10_000);
    }

    /**
     * Returns a copy with another subject claim (e.g. {@code oid} for Entra ID).
     *
     * @param claim subject claim
     * @return new settings
     */
    public IdentityClaimSettings withSubjectClaim(String claim) {
        return new IdentityClaimSettings(issuerOverride, claim, groupsClaim, rolesClaim, displayNameClaim, clearanceClaim,
                attributeClaims, localIssuer, ldapIssuer, detectGroupOverage, defaultClearance, cacheTtl, cacheMaxEntries);
    }

    /**
     * Returns a copy with other groups and roles claims.
     *
     * @param groups groups claim
     * @param roles  roles claim
     * @return new settings
     */
    public IdentityClaimSettings withGroupsAndRolesClaims(String groups, String roles) {
        return new IdentityClaimSettings(issuerOverride, subjectClaim, groups, roles, displayNameClaim, clearanceClaim,
                attributeClaims, localIssuer, ldapIssuer, detectGroupOverage, defaultClearance, cacheTtl, cacheMaxEntries);
    }

    /**
     * Returns a copy with a clearance claim and ABAC attribute claims.
     *
     * @param clearance  clearance claim, or {@code null}
     * @param attributes attribute name → claim
     * @return new settings
     */
    public IdentityClaimSettings withAttributeClaims(@Nullable String clearance, Map<String, String> attributes) {
        return new IdentityClaimSettings(issuerOverride, subjectClaim, groupsClaim, rolesClaim, displayNameClaim, clearance,
                attributes, localIssuer, ldapIssuer, detectGroupOverage, defaultClearance, cacheTtl, cacheMaxEntries);
    }

    /**
     * Returns a copy with an issuer override.
     *
     * @param issuer issuer to record, or {@code null}
     * @return new settings
     */
    public IdentityClaimSettings withIssuerOverride(@Nullable String issuer) {
        return new IdentityClaimSettings(issuer, subjectClaim, groupsClaim, rolesClaim, displayNameClaim, clearanceClaim,
                attributeClaims, localIssuer, ldapIssuer, detectGroupOverage, defaultClearance, cacheTtl, cacheMaxEntries);
    }

    private static void requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
