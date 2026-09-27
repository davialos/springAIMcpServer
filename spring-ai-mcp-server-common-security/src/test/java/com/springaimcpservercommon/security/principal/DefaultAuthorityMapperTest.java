package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.TestFixtures.InMemoryDirectory;
import com.springaimcpservercommon.security.TestFixtures.InMemoryMemberships;
import com.springaimcpservercommon.security.TestFixtures.MutableClock;
import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationToken;
import com.springaimcpservercommon.security.port.ApiKeyLookup.ApiKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAuthorityMapperTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID SALES = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    private final MutableClock clock = new MutableClock(NOW);
    private InMemoryDirectory directory;
    private InMemoryMemberships memberships;

    @BeforeEach
    void setUp() {
        directory = new InMemoryDirectory();
        memberships = new InMemoryMemberships();
    }

    private DefaultAuthorityMapper mapper(IdentityClaimSettings settings, List<RoleMappingRule> rules) {
        return DefaultAuthorityMapper.builder(directory, memberships).settings(settings).staticRules(rules).clock(clock).build();
    }

    private static JwtAuthenticationToken jwt(Consumer<Map<String, Object>> claims, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token-" + UUID.randomUUID())
                .header("alg", "RS256")
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(3600))
                .claims(claims)
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authorities));
    }

    @Test
    void entraJwtUsesOidAppRolesGroupsAndAttributeClaims() {
        IdentityClaimSettings settings = IdentityClaimSettings.defaults().withSubjectClaim("oid")
                .withAttributeClaims("data_clearance", Map.of("tenantId", "tid"));
        RoleMappingRule adminRole = new RoleMappingRule(MappingSource.OIDC_CLAIM, null, "roles", "Dai.PlatformAdmin",
                FrameworkRole.PLATFORM_ADMIN, null, 10);
        String oid = "5f2a1b7c-0000-4000-8000-00000000abcd";

        DaiPrincipal p = mapper(settings, List.of(adminRole)).map(jwt(c -> {
            c.put("iss", "https://login.microsoftonline.com/tenant-1/v2.0");
            c.put("sub", "pairwise-subject");
            c.put("oid", oid);
            c.put("tid", "tenant-1");
            c.put("roles", List.of("Dai.PlatformAdmin"));
            c.put("groups", List.of("11111111-group"));
            c.put("scp", "dai.mcp.read dai.mcp.agents");
            c.put("data_clearance", "confidential");
            c.put("azp", "client-123");
        }, "SCOPE_dai.mcp.read"));

        assertThat(p.subjectId()).isEqualTo(oid);
        assertThat(p.issuer()).isEqualTo("https://login.microsoftonline.com/tenant-1/v2.0");
        assertThat(p.type()).isEqualTo(SubjectType.USER);
        assertThat(p.globalRoles()).containsExactly(FrameworkRole.PLATFORM_ADMIN);
        assertThat(p.externalGroups()).containsExactly("11111111-group");
        assertThat(p.attributes()).containsEntry("tenantId", "tenant-1");
        assertThat(p.scopes()).contains("dai.mcp.read", "dai.mcp.agents");
        assertThat(p.clearance()).isEqualTo(Classification.CONFIDENTIAL);
    }

    @Test
    void oktaGroupsMatchPrefixGlobIntoWorkspaceRole() {
        RoleMappingRule rule = RoleMappingRule.group("groups", "sg-sales-*", FrameworkRole.AUTHOR, SALES);
        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of(rule)).map(jwt(c -> {
            c.put("iss", "https://acme.okta.com/oauth2/default");
            c.put("sub", "00u1abcd");
            c.put("groups", List.of("sg-sales-analysts", "everyone"));
        }));
        assertThat(p.workspaceRoles()).containsEntry(SALES, Set.of(FrameworkRole.AUTHOR));
        assertThat(p.globalRoles()).isEmpty();
        assertThat(p.clearance()).isEqualTo(Classification.INTERNAL);
    }

    @Test
    void keycloakRealmRolesViaDottedClaimPath() {
        IdentityClaimSettings settings = IdentityClaimSettings.defaults().withGroupsAndRolesClaims("groups", "realm_access.roles");
        RoleMappingRule rule = new RoleMappingRule(MappingSource.OIDC_CLAIM, "https://kc.example.com/realms/acme",
                "realm_access.roles", "dai-security", FrameworkRole.SECURITY_ADMIN, null, 100);
        RoleMappingRule otherIssuer = new RoleMappingRule(MappingSource.OIDC_CLAIM, "https://other.example.com",
                "realm_access.roles", "dai-security", FrameworkRole.PLATFORM_ADMIN, null, 100);
        DaiPrincipal p = mapper(settings, List.of(rule, otherIssuer)).map(jwt(c -> {
            c.put("iss", "https://kc.example.com/realms/acme");
            c.put("sub", "f:kc:42");
            c.put("realm_access", Map.of("roles", List.of("dai-security", "offline_access")));
        }));
        assertThat(p.globalRoles()).containsExactly(FrameworkRole.SECURITY_ADMIN);
    }

    /** LDAP principal exposing {@code getDn()} like {@code LdapUserDetails}. */
    public static final class LdapLikeUser {
        public String getDn() {
            return "uid=jdoe, ou=people, dc=acme, dc=com";
        }

        @Override
        public String toString() {
            return "jdoe";
        }
    }

    @Test
    void ldapDnGroupsMatchCaseInsensitively() {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("cn=data-owners,ou=groups,dc=acme,dc=com"),
                new SimpleGrantedAuthority("ROLE_USER"));
        var auth = UsernamePasswordAuthenticationToken.authenticated(new LdapLikeUser(), null, authorities);
        RoleMappingRule rule = RoleMappingRule.ldapGroup("CN=Data-Owners, OU=groups,DC=acme,DC=com", FrameworkRole.AUDITOR, null);

        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of(rule)).map(auth);

        assertThat(p.subjectId()).isEqualTo("uid=jdoe,ou=people,dc=acme,dc=com");
        assertThat(p.issuer()).isEqualTo("ldap:host");
        assertThat(p.externalGroups()).containsExactly("cn=data-owners,ou=groups,dc=acme,dc=com");
        assertThat(p.globalRoles()).containsExactly(FrameworkRole.AUDITOR);
    }

    @Test
    void authorityMappingAndEmailSubjectsArePseudonymised() {
        var auth = UsernamePasswordAuthenticationToken.authenticated("alice@example.com", null,
                AuthorityUtils.createAuthorityList("ROLE_PLATFORM_ADMIN"));
        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(),
                List.of(RoleMappingRule.authority("ROLE_PLATFORM_ADMIN", FrameworkRole.PLATFORM_ADMIN, null))).map(auth);
        assertThat(p.subjectId()).startsWith("sha256:").doesNotContain("alice");
        assertThat(p.displayName()).isNull();
        assertThat(p.issuer()).isEqualTo("host");
        assertThat(p.globalRoles()).containsExactly(FrameworkRole.PLATFORM_ADMIN);
    }

    @Test
    void groupOverageUsesGroupResolverAndFailsClosedWithoutOne() {
        Consumer<Map<String, Object>> overage = c -> {
            c.put("iss", "https://login.microsoftonline.com/t/v2.0");
            c.put("sub", "s1");
            c.put("_claim_names", Map.of("groups", "src1"));
        };
        DaiPrincipal withoutResolver = mapper(IdentityClaimSettings.defaults(), List.of()).map(jwt(overage));
        assertThat(withoutResolver.externalGroups()).isEmpty();

        DefaultAuthorityMapper withResolver = DefaultAuthorityMapper.builder(directory, memberships)
                .groupResolver(req -> Set.of("resolved-group")).clock(clock).build();
        assertThat(withResolver.map(jwt(overage)).externalGroups()).containsExactly("resolved-group");
    }

    @Test
    void membershipsOfTheUserAndItsGroupsAreMerged() {
        String issuer = "https://idp.example.com";
        UUID groupId = directory.group(issuer, "team-a");
        memberships.add(groupId, SALES, FrameworkRole.APPROVER);
        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of()).map(jwt(c -> {
            c.put("iss", issuer);
            c.put("sub", "u-1");
            c.put("groups", List.of("team-a"));
        }));
        assertThat(p.workspaceRoles()).containsEntry(SALES, Set.of(FrameworkRole.APPROVER));
    }

    @Test
    void oidcLoginIsMapped() {
        OidcIdToken idToken = new OidcIdToken("id-token", NOW.minusSeconds(10), NOW.plusSeconds(600),
                Map.of("iss", "https://accounts.example.com", "sub", "oidc-sub", "name", "Jane Doe",
                        "groups", List.of("g-1"), "auth_time", NOW.minusSeconds(10).getEpochSecond()));
        Collection<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("OIDC_USER");
        var auth = new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "acme");

        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of()).map(auth);
        assertThat(p.subjectId()).isEqualTo("oidc-sub");
        assertThat(p.displayName()).isEqualTo("Jane Doe");
        assertThat(p.externalGroups()).containsExactly("g-1");
        assertThat(p.authenticatedAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void opaqueTokenIntrospectionIsMapped() {
        Map<String, Object> attributes = Map.of("iss", "https://as.example.com", "sub", "opaque-sub",
                "scope", List.of("dai.mcp.read"), "client_id", "desktop-client");
        var principal = new DefaultOAuth2AuthenticatedPrincipal(attributes, AuthorityUtils.createAuthorityList("SCOPE_dai.mcp.read"));
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "opaque-value", NOW, NOW.plusSeconds(300));
        var auth = new BearerTokenAuthentication(principal, token, principal.getAuthorities());

        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of()).map(auth);
        assertThat(p.subjectId()).isEqualTo("opaque-sub");
        assertThat(p.scopes()).containsExactly("dai.mcp.read");
        assertThat(IdentityExtraction.defaults().extract(auth, IdentityClaimSettings.defaults()).kind())
                .isEqualTo(IdentityKind.OPAQUE_TOKEN);
    }

    @Test
    void resultsAreCachedWithinTtlAndTokenLifetime() {
        DefaultAuthorityMapper mapper = mapper(IdentityClaimSettings.defaults(), List.of());
        JwtAuthenticationToken auth = jwt(c -> {
            c.put("iss", "https://idp.example.com");
            c.put("sub", "cached");
        });
        DaiPrincipal first = mapper.map(auth);
        DaiPrincipal second = mapper.map(auth);
        assertThat(second).isSameAs(first);
        assertThat(directory.resolveCalls).hasValue(1);

        clock.advance(Duration.ofMinutes(3)); // default TTL is 2 minutes
        mapper.map(auth);
        assertThat(directory.resolveCalls).hasValue(2);
    }

    @Test
    void anonymousAndUnauthenticatedCallersAreRejected() {
        DefaultAuthorityMapper mapper = mapper(IdentityClaimSettings.defaults(), List.of());
        var anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThatThrownBy(() -> mapper.map(anonymous)).isInstanceOf(PrincipalMappingException.class);
        assertThatThrownBy(() -> mapper.map(UsernamePasswordAuthenticationToken.unauthenticated("u", "p")))
                .isInstanceOf(PrincipalMappingException.class);
    }

    @Test
    void apiKeyAuthenticationMapsToServiceAccount() {
        UUID saPrincipal = UUID.randomUUID();
        UUID saId = UUID.randomUUID();
        ApiKeyRecord key = new ApiKeyRecord(UUID.randomUUID(), "dai_prod_ABCDEFGH1234", "v1:x", "hmac-sha256",
                NOW.plusSeconds(3600), null, null, saId, saPrincipal, "etl-bot", true, SALES,
                Set.of("tool:invoke"), List.of());
        DaiPrincipal p = mapper(IdentityClaimSettings.defaults(), List.of()).map(ApiKeyAuthenticationToken.authenticated(key));
        assertThat(p.type()).isEqualTo(SubjectType.SERVICE_ACCOUNT);
        assertThat(p.issuer()).isEqualTo("dai");
        assertThat(p.principalId()).isEqualTo(saPrincipal);
        assertThat(p.subjectId()).isEqualTo(saId.toString());
        assertThat(p.scopes()).containsExactly("tool:invoke");
    }

    @Test
    void invalidRulesAreRejected() {
        assertThatThrownBy(() -> RoleMappingRule.authority("X", FrameworkRole.AUTHOR, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleMappingRule.authority("X", FrameworkRole.PLATFORM_ADMIN, SALES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleMappingRule(MappingSource.OIDC_CLAIM, null, null, "x", FrameworkRole.AUDITOR, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
