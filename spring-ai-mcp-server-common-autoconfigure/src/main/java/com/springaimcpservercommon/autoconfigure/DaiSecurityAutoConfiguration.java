package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.security.principal.DefaultAuthorityMapper;
import com.springaimcpservercommon.security.principal.GroupPrincipalIds;
import com.springaimcpservercommon.security.principal.IdentityClaimSettings;
import com.springaimcpservercommon.security.principal.IdentityExtraction;
import com.springaimcpservercommon.security.principal.PrincipalAttributeResolver;
import com.springaimcpservercommon.security.principal.RoleMappingRule;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.MembershipSource;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;
import com.springaimcpservercommon.security.port.ResourceStatusView;
import com.springaimcpservercommon.security.port.RoleMappingSource;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for principal mapping and authorization.
 *
 * <p>Activated only when {@link AuthorizationEngine} is on the classpath (security module present).
 * The core security ports ({@link GrantSource}, {@link KillSwitchView}, {@link ResourceStatusView},
 * {@link PrincipalDirectoryPort}, {@link MembershipSource}) must be provided by the persistence
 * module's auto-configuration or by the host application — this class does not supply them.
 */
@AutoConfiguration(after = DaiCoreAutoConfiguration.class)
@ConditionalOnClass(AuthorizationEngine.class)
@NullMarked
public class DaiSecurityAutoConfiguration {

    /**
     * The principal-mapping cache and group resolver, shared between {@link DefaultAuthorityMapper}
     * and {@link AuthorizationEngine} so both see consistent group identities (SEC-01 §3).
     *
     * @param directory principal directory port
     * @param props     framework properties
     * @return the shared resolver
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PrincipalDirectoryPort.class)
    public GroupPrincipalIds groupPrincipalIds(PrincipalDirectoryPort directory, DaiProperties props) {
        DaiProperties.Security sec = props.security();
        Duration ttl = clampCacheTtl(sec.cacheTtl());
        return new GroupPrincipalIds(directory, Clock.systemUTC(), ttl, sec.cacheMaxEntries());
    }

    /**
     * Default identity claim settings, populated from {@link DaiProperties}.
     *
     * @param props framework properties
     * @return the settings
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentityClaimSettings identityClaimSettings(DaiProperties props) {
        DaiProperties.Security sec = props.security();
        IdentityClaimSettings base = IdentityClaimSettings.defaults();
        Duration ttl = clampCacheTtl(sec.cacheTtl());
        Classification clearance = sec.defaultClearance() != null ? sec.defaultClearance() : Classification.INTERNAL;
        return new IdentityClaimSettings(
                sec.issuerOverride(),
                base.subjectClaim(),
                sec.groupsClaim() != null ? sec.groupsClaim() : base.groupsClaim(),
                base.rolesClaim(),
                base.displayNameClaim(),
                sec.clearanceClaim(),
                sec.attributeClaims() != null ? Map.copyOf(sec.attributeClaims()) : Map.of(),
                sec.localIssuer() != null ? sec.localIssuer() : base.localIssuer(),
                base.ldapIssuer(),
                base.detectGroupOverage(),
                clearance,
                ttl,
                sec.cacheMaxEntries());
    }

    /**
     * Default authority mapper. Requires {@link PrincipalDirectoryPort} and {@link MembershipSource}
     * beans to be available (provided by the persistence autoconfigure or the host).
     *
     * @param directory           principal directory port
     * @param memberships         workspace membership port
     * @param groupPrincipalIds   shared group resolver
     * @param roleMappingSource   DB-backed role mappings
     * @param claimSettings       identity claim settings
     * @param attributeResolvers  optional ABAC attribute resolvers contributed by the host
     * @param props               framework properties
     * @return the mapper
     */
    @Bean
    @ConditionalOnMissingBean(AuthorityMapper.class)
    @ConditionalOnBean({PrincipalDirectoryPort.class, MembershipSource.class})
    public DefaultAuthorityMapper authorityMapper(
            PrincipalDirectoryPort directory,
            MembershipSource memberships,
            GroupPrincipalIds groupPrincipalIds,
            RoleMappingSource roleMappingSource,
            IdentityClaimSettings claimSettings,
            List<PrincipalAttributeResolver> attributeResolvers,
            DaiProperties props) {
        List<RoleMappingRule> staticRules = buildStaticRules(props.security().staticRoleMappings());
        return DefaultAuthorityMapper.builder(directory, memberships)
                .settings(claimSettings)
                .roleMappingSource(roleMappingSource)
                .staticRules(staticRules)
                .attributeResolvers(attributeResolvers)
                .groupPrincipalIds(groupPrincipalIds)
                .identityExtraction(IdentityExtraction.defaults())
                .build();
    }

    /**
     * Authorization decision engine. Requires the four core port beans.
     *
     * @param grants          grant source port
     * @param killSwitches    kill switch view port
     * @param resourceStatus  resource status view port
     * @param groupPrincipalIds shared group resolver
     * @return the engine
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({GrantSource.class, KillSwitchView.class, ResourceStatusView.class})
    public AuthorizationEngine authorizationEngine(
            GrantSource grants,
            KillSwitchView killSwitches,
            ResourceStatusView resourceStatus,
            GroupPrincipalIds groupPrincipalIds) {
        return AuthorizationEngine.builder(grants, killSwitches, resourceStatus, groupPrincipalIds)
                .clock(Clock.systemUTC())
                .build();
    }

    private static Duration clampCacheTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return Duration.ofMinutes(2);
        }
        return ttl.compareTo(IdentityClaimSettings.MAX_CACHE_TTL) > 0
                ? IdentityClaimSettings.MAX_CACHE_TTL : ttl;
    }

    private static List<RoleMappingRule> buildStaticRules(
            List<DaiProperties.StaticRoleMapping> configs) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        List<RoleMappingRule> rules = new ArrayList<>(configs.size());
        for (DaiProperties.StaticRoleMapping cfg : configs) {
            try {
                com.springaimcpservercommon.security.principal.MappingSource source =
                        com.springaimcpservercommon.security.principal.MappingSource.valueOf(cfg.source());
                com.springaimcpservercommon.core.principal.FrameworkRole role =
                        com.springaimcpservercommon.core.principal.FrameworkRole.valueOf(cfg.role());
                rules.add(new RoleMappingRule(source, null, cfg.claimName(), cfg.matchValue(), role, cfg.workspaceId(), 100));
            } catch (IllegalArgumentException e) {
                // Bad config value — skip; the host's context will fail to start if strict validation is configured
            }
        }
        return List.copyOf(rules);
    }
}
