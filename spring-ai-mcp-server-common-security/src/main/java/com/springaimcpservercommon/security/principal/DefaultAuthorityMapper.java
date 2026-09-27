package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.internal.GlobPattern;
import com.springaimcpservercommon.security.internal.TtlCache;
import com.springaimcpservercommon.security.port.MembershipSource;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;
import com.springaimcpservercommon.security.port.RoleMappingSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Default {@link AuthorityMapper} (SEC-01 §3): a rule engine over static bootstrap {@link RoleMappingRule}s and the
 * enabled {@code dai_role_mapping} rows, plus workspace memberships of the principal and its groups.
 *
 * <p>Supported authentications: resource-server JWT (Entra {@code oid}/{@code roles}, Okta {@code groups}, Keycloak
 * {@code realm_access.roles}, …), OIDC login, opaque-token introspection, SAML 2.0, LDAP/Active Directory,
 * {@code UserDetails}/generic authorities and our API keys ({@link ServiceAccountAuthentication}).
 *
 * <p>Rules and guarantees:
 * <ul>
 *   <li>Subject ids are never e-mail addresses: a subject (or the configured subject claim) that looks like one is
 *       replaced by its {@code sha256:} digest (stable pseudonym); e-mail-like display names are dropped.</li>
 *   <li>Groups = values of the groups claim / SAML attribute + LDAP group DNs + {@link GroupResolver} results on
 *       Entra group overage. Without a resolver, overage yields no groups (fail closed) and a warning.</li>
 *   <li>Matching rules are unioned; a mapping can only add roles. Workspace roles from memberships of the principal and
 *       of its group principals are added.</li>
 *   <li>Clearance comes from the clearance claim if it names a concrete {@link Classification}, else the default
 *       (INTERNAL).</li>
 *   <li>Results are cached for at most {@link IdentityClaimSettings#cacheTtl()} (≤ 5 min) and never beyond the token's
 *       expiry, keyed by issuer + subject + token/session digest + authorities + scopes.</li>
 *   <li>Nothing but subject/principal ids is logged.</li>
 * </ul>
 */
public final class DefaultAuthorityMapper implements AuthorityMapper {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthorityMapper.class);
    private static final int MAX_ID_LENGTH = 512;

    private final IdentityClaimSettings settings;
    private final PrincipalDirectoryPort directory;
    private final MembershipSource memberships;
    private final RoleMappingSource mappingSource;
    private final List<RoleMappingRule> staticRules;
    private final List<PrincipalAttributeResolver> attributeResolvers;
    private final @Nullable GroupResolver groupResolver;
    private final IdentityExtraction extraction;
    private final GroupPrincipalIds groupPrincipalIds;
    private final Clock clock;
    private final TtlCache<String, DaiPrincipal> cache;
    private final TtlCache<String, GlobPattern> globs;

    private DefaultAuthorityMapper(Builder b) {
        this.settings = b.settings;
        this.directory = b.directory;
        this.memberships = b.memberships;
        this.mappingSource = b.mappingSource;
        this.staticRules = List.copyOf(b.staticRules);
        this.attributeResolvers = List.copyOf(b.attributeResolvers);
        this.groupResolver = b.groupResolver;
        this.extraction = b.extraction;
        this.clock = b.clock;
        this.groupPrincipalIds = b.groupPrincipalIds != null ? b.groupPrincipalIds
                : new GroupPrincipalIds(b.directory, b.clock, settings.cacheTtl(), settings.cacheMaxEntries());
        this.cache = new TtlCache<>(settings.cacheMaxEntries(), settings.cacheTtl(), b.clock);
        this.globs = new TtlCache<>(2_048, Duration.ofHours(1), b.clock);
    }

    /**
     * Starts a builder.
     *
     * @param directory   principal directory port
     * @param memberships workspace membership port
     * @return a builder
     */
    public static Builder builder(PrincipalDirectoryPort directory, MembershipSource memberships) {
        return new Builder(directory, memberships);
    }

    @Override
    public DaiPrincipal map(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new PrincipalMappingException("no authenticated caller");
        }
        if (authentication instanceof ServiceAccountAuthentication serviceAccount) {
            return mapServiceAccount(serviceAccount);
        }
        ExtractedIdentity identity = extraction.extract(authentication, settings);
        String issuer = settings.issuerOverride() != null ? settings.issuerOverride()
                : identity.issuer() != null ? identity.issuer() : settings.localIssuer();
        if (issuer.isBlank() || issuer.length() > MAX_ID_LENGTH) {
            throw new PrincipalMappingException("issuer missing or too long");
        }
        String subject = stableSubject(identity.subject());

        String cacheKey = cacheKey(identity, issuer, subject);
        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        Instant now = clock.instant();
        String displayName = safeDisplayName(identity.displayName());
        Set<String> groups = groups(identity, issuer, subject);
        SubjectType type = subjectType(identity, subject);
        UUID principalId = directory.resolvePrincipalId(type, issuer, subject, displayName);

        Set<FrameworkRole> globalRoles = EnumSet.noneOf(FrameworkRole.class);
        Map<UUID, Set<FrameworkRole>> workspaceRoles = new HashMap<>();
        applyRules(identity, issuer, groups, globalRoles, workspaceRoles);

        Set<UUID> subjectIds = new HashSet<>(groupPrincipalIds.resolve(issuer, groups));
        subjectIds.add(principalId);
        merge(workspaceRoles, memberships.findWorkspaceRoles(subjectIds, now));

        DaiPrincipal principal = new DaiPrincipal(principalId, type, issuer, subject, displayName, groups, globalRoles,
                freeze(workspaceRoles), attributes(identity, authentication), clearance(identity), identity.authenticatedAt(),
                identity.scopes());
        cache.put(cacheKey, principal, identity.expiresAt());
        log.debug("mapped principal {} ({}, {} global roles, {} workspaces)", principalId, identity.kind(),
                globalRoles.size(), workspaceRoles.size());
        return principal;
    }

    /**
     * Drops all cached mapping results, e.g. after role mappings or memberships changed.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        groupPrincipalIds.invalidateAll();
    }

    /**
     * Returns the group-principal resolver shared with the authorization engine.
     *
     * @return the resolver
     */
    public GroupPrincipalIds groupPrincipalIds() {
        return groupPrincipalIds;
    }

    private DaiPrincipal mapServiceAccount(ServiceAccountAuthentication authentication) {
        ServiceAccountAuthentication.Identity sa = authentication.serviceAccount();
        String cacheKey = Sha256.of("sa\n" + sa.credentialId() + '\n' + String.join(",", new TreeSet<>(sa.permissions())));
        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        Map<UUID, Set<FrameworkRole>> workspaceRoles = new HashMap<>();
        merge(workspaceRoles, memberships.findWorkspaceRoles(Set.of(sa.principalId()), clock.instant()));
        Map<String, Object> attributes = new LinkedHashMap<>();
        applyResolvers(authentication, attributes);
        DaiPrincipal principal = new DaiPrincipal(sa.principalId(), SubjectType.SERVICE_ACCOUNT,
                ServiceAccountAuthentication.ISSUER, sa.serviceAccountId().toString(), sa.name(), Set.of(), Set.of(),
                freeze(workspaceRoles), attributes, settings.defaultClearance(), null, sa.permissions());
        cache.put(cacheKey, principal, sa.expiresAt());
        return principal;
    }

    private static String stableSubject(String raw) {
        String subject = raw.trim();
        if (subject.isEmpty()) {
            throw new PrincipalMappingException("authentication has no subject");
        }
        if (ClaimValues.looksLikeEmail(subject) || subject.length() > MAX_ID_LENGTH) {
            return Sha256.of(subject.toLowerCase(Locale.ROOT));
        }
        return subject;
    }

    private static @Nullable String safeDisplayName(@Nullable String displayName) {
        if (displayName == null || ClaimValues.looksLikeEmail(displayName)) {
            return null;
        }
        return displayName.length() > 256 ? displayName.substring(0, 256) : displayName;
    }

    private static SubjectType subjectType(ExtractedIdentity identity, String subject) {
        Object idtyp = identity.claims().get("idtyp");
        boolean appOnly = "app".equals(idtyp)
                || (identity.clientId() != null && identity.clientId().equals(subject)
                && identity.kind() == IdentityKind.JWT_BEARER);
        return appOnly ? SubjectType.SERVICE_ACCOUNT : SubjectType.USER;
    }

    private Set<String> groups(ExtractedIdentity identity, String issuer, String subject) {
        Set<String> groups = new LinkedHashSet<>(ClaimValues.strings(ClaimValues.lookup(identity.claims(), settings.groupsClaim())));
        groups.addAll(identity.ldapGroups());
        if (settings.detectGroupOverage() && hasGroupOverage(identity.claims())) {
            if (groupResolver == null) {
                log.warn("group overage for subject {} but no GroupResolver is configured: groups ignored", subject);
            } else {
                try {
                    groups.addAll(groupResolver.resolveGroups(new GroupResolver.Request(issuer, subject, identity.claims())));
                } catch (RuntimeException e) {
                    log.warn("GroupResolver failed for subject {}: groups ignored ({})", subject, e.getClass().getSimpleName());
                }
            }
        }
        groups.removeIf(g -> g.isBlank() || g.length() > MAX_ID_LENGTH);
        return groups;
    }

    private boolean hasGroupOverage(Map<String, Object> claims) {
        if (claims.get("_claim_names") instanceof Map<?, ?> names
                && (names.containsKey("groups") || names.containsKey(settings.groupsClaim()))) {
            return true;
        }
        Object hasGroups = claims.get("hasgroups");
        return Boolean.TRUE.equals(hasGroups) || "true".equals(hasGroups);
    }

    private void applyRules(ExtractedIdentity identity, String issuer, Set<String> groups,
                            Set<FrameworkRole> globalRoles, Map<UUID, Set<FrameworkRole>> workspaceRoles) {
        List<RoleMappingRule> rules = new ArrayList<>(staticRules);
        rules.addAll(mappingSource.findEnabledMappings());
        for (RoleMappingRule rule : rules) {
            if (rule.issuer() != null && !rule.issuer().equals(issuer)) {
                continue;
            }
            if (!matches(rule, identity, groups)) {
                continue;
            }
            if (rule.workspaceId() == null) {
                globalRoles.add(rule.role());
            } else {
                workspaceRoles.computeIfAbsent(rule.workspaceId(), k -> EnumSet.noneOf(FrameworkRole.class)).add(rule.role());
            }
        }
    }

    private boolean matches(RoleMappingRule rule, ExtractedIdentity identity, Set<String> groups) {
        boolean ldap = rule.source() == MappingSource.LDAP_GROUP;
        Set<String> candidates = switch (rule.source()) {
            case AUTHORITY -> identity.authorities();
            case SCOPE -> identity.scopes();
            case LDAP_GROUP -> identity.ldapGroups();
            case OIDC_CLAIM -> Objects.equals(rule.claimName(), settings.groupsClaim())
                    ? groups
                    : ClaimValues.strings(ClaimValues.lookup(identity.claims(), Objects.requireNonNull(rule.claimName())));
        };
        if (candidates.isEmpty()) {
            return false;
        }
        String value = ldap ? ClaimValues.normalizeDn(rule.matchValue()) : rule.matchValue();
        String globKey = (ldap ? "i:" : "s:") + value;
        GlobPattern glob = globs.get(globKey).orElseGet(() -> {
            GlobPattern compiled = GlobPattern.compile(value, ldap);
            globs.put(globKey, compiled);
            return compiled;
        });
        for (String candidate : candidates) {
            if (glob.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> attributes(ExtractedIdentity identity, Authentication authentication) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        settings.attributeClaims().forEach((name, claim) -> {
            Object value = ClaimValues.simpleValue(ClaimValues.lookup(identity.claims(), claim));
            if (value != null) {
                attributes.put(name, value);
            }
        });
        applyResolvers(authentication, attributes);
        return attributes;
    }

    private void applyResolvers(Authentication authentication, Map<String, Object> attributes) {
        for (PrincipalAttributeResolver resolver : attributeResolvers) {
            try {
                resolver.resolve(authentication).forEach((name, raw) -> {
                    Object value = ClaimValues.simpleValue(raw);
                    if (name != null && !name.isBlank() && value != null) {
                        attributes.put(name, value);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("PrincipalAttributeResolver {} failed: contribution skipped ({})",
                        resolver.getClass().getName(), e.getClass().getSimpleName());
            }
        }
    }

    private Classification clearance(ExtractedIdentity identity) {
        String claim = settings.clearanceClaim();
        if (claim == null) {
            return settings.defaultClearance();
        }
        String value = ClaimValues.string(ClaimValues.lookup(identity.claims(), claim));
        if (value == null) {
            return settings.defaultClearance();
        }
        try {
            Classification parsed = Classification.valueOf(value.trim().toUpperCase(Locale.ROOT));
            return parsed == Classification.INHERIT ? settings.defaultClearance() : parsed;
        } catch (IllegalArgumentException e) {
            log.debug("unrecognised clearance claim value ignored");
            return settings.defaultClearance();
        }
    }

    private static String cacheKey(ExtractedIdentity identity, String issuer, String subject) {
        return Sha256.of(identity.kind() + "\n" + issuer + '\n' + subject + '\n'
                + Objects.requireNonNullElse(identity.credentialDigest(), "-") + '\n'
                + String.join(",", new TreeSet<>(identity.authorities())) + '\n'
                + String.join(" ", new TreeSet<>(identity.scopes())));
    }

    private static void merge(Map<UUID, Set<FrameworkRole>> target, Map<UUID, Set<FrameworkRole>> source) {
        source.forEach((workspaceId, roles) -> {
            Set<FrameworkRole> merged = target.computeIfAbsent(workspaceId, k -> EnumSet.noneOf(FrameworkRole.class));
            for (FrameworkRole role : roles) {
                if (!role.globalOnly()) {
                    merged.add(role);
                }
            }
        });
    }

    private static Map<UUID, Set<FrameworkRole>> freeze(Map<UUID, Set<FrameworkRole>> map) {
        Map<UUID, Set<FrameworkRole>> frozen = new HashMap<>();
        map.forEach((k, v) -> {
            if (!v.isEmpty()) {
                frozen.put(k, Set.copyOf(v));
            }
        });
        return frozen;
    }

    /**
     * Builder of {@link DefaultAuthorityMapper}.
     */
    public static final class Builder {
        private final PrincipalDirectoryPort directory;
        private final MembershipSource memberships;
        private IdentityClaimSettings settings = IdentityClaimSettings.defaults();
        private RoleMappingSource mappingSource = List::of;
        private List<RoleMappingRule> staticRules = List.of();
        private List<PrincipalAttributeResolver> attributeResolvers = List.of();
        private @Nullable GroupResolver groupResolver;
        private IdentityExtraction extraction = IdentityExtraction.defaults();
        private @Nullable GroupPrincipalIds groupPrincipalIds;
        private Clock clock = Clock.systemUTC();

        private Builder(PrincipalDirectoryPort directory, MembershipSource memberships) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.memberships = Objects.requireNonNull(memberships, "memberships");
        }

        /**
         * Sets claim settings.
         *
         * @param settings settings
         * @return this builder
         */
        public Builder settings(IdentityClaimSettings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /**
         * Sets the source of DB mappings.
         *
         * @param source mapping source
         * @return this builder
         */
        public Builder roleMappingSource(RoleMappingSource source) {
            this.mappingSource = Objects.requireNonNull(source, "source");
            return this;
        }

        /**
         * Sets static bootstrap rules from configuration.
         *
         * @param rules rules
         * @return this builder
         */
        public Builder staticRules(List<RoleMappingRule> rules) {
            this.staticRules = List.copyOf(rules);
            return this;
        }

        /**
         * Sets attribute resolvers, applied in order.
         *
         * @param resolvers resolvers
         * @return this builder
         */
        public Builder attributeResolvers(List<PrincipalAttributeResolver> resolvers) {
            this.attributeResolvers = List.copyOf(resolvers);
            return this;
        }

        /**
         * Sets the group resolver for group overage.
         *
         * @param resolver resolver, or {@code null}
         * @return this builder
         */
        public Builder groupResolver(@Nullable GroupResolver resolver) {
            this.groupResolver = resolver;
            return this;
        }

        /**
         * Replaces the identity extraction chain.
         *
         * @param extraction chain
         * @return this builder
         */
        public Builder identityExtraction(IdentityExtraction extraction) {
            this.extraction = Objects.requireNonNull(extraction, "extraction");
            return this;
        }

        /**
         * Shares a group-principal resolver (e.g. with the authorization engine).
         *
         * @param ids resolver
         * @return this builder
         */
        public Builder groupPrincipalIds(GroupPrincipalIds ids) {
            this.groupPrincipalIds = Objects.requireNonNull(ids, "ids");
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the mapper.
         *
         * @return the mapper
         */
        public DefaultAuthorityMapper build() {
            return new DefaultAuthorityMapper(this);
        }
    }
}
