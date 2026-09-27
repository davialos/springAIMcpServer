package com.springaimcpservercommon.security.mcp;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.TestFixtures;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.DenyReason;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.security.permission.McpScope;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.McpClientRegistryPort;
import com.springaimcpservercommon.security.principal.GroupPrincipalIds;
import com.springaimcpservercommon.security.principal.IdentityClaimSettings;
import com.springaimcpservercommon.security.principal.IdentityExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpScopeEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID WS = UUID.fromString("0190a000-0000-7000-8000-00000000dddd");
    private static final String PRM = "https://app.example.com/.well-known/oauth-protected-resource/dynamic-ai/mcp";

    private TestFixtures.InMemoryGrants grants;
    private TestFixtures.InMemoryKillSwitches killSwitches;
    private TestFixtures.RecordingAudit audit;
    private McpScopeEvaluator evaluator;
    private ResourceRef binding;

    @BeforeEach
    void setUp() {
        TestFixtures.MutableClock clock = new TestFixtures.MutableClock(NOW);
        grants = new TestFixtures.InMemoryGrants();
        killSwitches = new TestFixtures.InMemoryKillSwitches();
        audit = new TestFixtures.RecordingAudit();
        AuthorizationEngine engine = AuthorizationEngine.builder(grants, killSwitches, new TestFixtures.InMemoryResourceStatus(),
                        new GroupPrincipalIds(new TestFixtures.InMemoryDirectory(), clock, Duration.ofMinutes(1), 10))
                .auditListeners(List.of(audit)).clock(clock).build();
        evaluator = new McpScopeEvaluator(engine, PRM);
        binding = new ResourceRef(WS, UUID.randomUUID(), "TOOL_BINDING", "find-orders", Classification.INTERNAL);
    }

    private static DaiPrincipal userWithScopes(String... scopes) {
        return TestFixtures.user(UUID.randomUUID(), Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, Set.of(scopes));
    }

    @Test
    void readToolNeedsReadScopeAndGrant() {
        DaiPrincipal user = userWithScopes("dai.mcp.read");
        McpToolRequirement read = new McpToolRequirement(McpToolRequirement.Kind.READ, "find_orders", binding);
        assertThat(evaluator.evaluate(user, read)).isInstanceOfSatisfying(McpScopeDecision.Denied.class,
                d -> assertThat(d.outcome().reason()).isEqualTo(DenyReason.NO_MATCHING_GRANT));

        grants.add(WS, user.principalId(), "tool:invoke", binding.resourceId(), null, null, null);
        assertThat(evaluator.evaluate(user, read)).isInstanceOf(McpScopeDecision.Allowed.class);
        assertThat(evaluator.hasScope(user, McpScope.READ)).isTrue();
    }

    @Test
    void missingScopeYieldsStepUpChallengeEvenWithGrants() {
        DaiPrincipal user = userWithScopes("dai.mcp.read", "openid");
        grants.add(WS, user.principalId(), "tool:invoke", null, null, null, null);
        grants.add(WS, user.principalId(), "data:write-propose", null, null, null, null);
        McpToolRequirement write = new McpToolRequirement(McpToolRequirement.Kind.WRITE, "update_order", binding);

        assertThat(evaluator.evaluate(user, write)).isInstanceOfSatisfying(McpScopeDecision.InsufficientScope.class, s -> {
            assertThat(s.requiredScopes()).containsExactly("dai.mcp.read", "dai.mcp.propose");
            assertThat(s.wwwAuthenticate()).isEqualTo("Bearer error=\"insufficient_scope\", "
                    + "scope=\"dai.mcp.read dai.mcp.propose\", resource_metadata=\"" + PRM + "\", "
                    + "error_description=\"Additional scope dai.mcp.propose required\"");
        });
        assertThat(audit.events).singleElement()
                .satisfies(e -> assertThat(e.outcome().granted()).isFalse());
    }

    @Test
    void writeToolsNeedBothInvokeAndProposePermissions() {
        DaiPrincipal user = userWithScopes("dai.mcp.read", "dai.mcp.propose");
        grants.add(WS, user.principalId(), "tool:invoke", null, null, null, null);
        McpToolRequirement write = new McpToolRequirement(McpToolRequirement.Kind.WRITE, "update_order", binding);
        assertThat(evaluator.evaluate(user, write)).isInstanceOf(McpScopeDecision.Denied.class);

        grants.add(WS, user.principalId(), "data:write-propose", null, null, null, null);
        assertThat(evaluator.evaluate(user, write)).isInstanceOfSatisfying(McpScopeDecision.Allowed.class,
                a -> assertThat(a.permits()).hasSize(2));

        killSwitches.byTool.put("update_order", new KillSwitchView.ActiveKillSwitch(UUID.randomUUID(), KillSwitchView.Scope.TOOL));
        assertThat(evaluator.evaluate(user, write)).isInstanceOfSatisfying(McpScopeDecision.Denied.class,
                d -> assertThat(d.outcome().reason()).isEqualTo(DenyReason.KILL_SWITCH));
    }

    @Test
    void apiKeysUseMcpPermissionsAndCannotStepUp() {
        UUID sa = UUID.randomUUID();
        DaiPrincipal key = new DaiPrincipal(sa, SubjectType.SERVICE_ACCOUNT, "dai", "sa", "bot", Set.of(), Set.of(),
                Map.of(), Map.of(), Classification.INTERNAL, null, Set.of("mcp:read", "tool:invoke"));
        grants.add(WS, sa, "tool:invoke", null, null, null, null);
        grants.add(WS, sa, "agent:invoke", null, null, null, null);
        ResourceRef agent = new ResourceRef(WS, UUID.randomUUID(), "AGENT", "helper", Classification.INTERNAL);

        assertThat(evaluator.evaluate(key, new McpToolRequirement(McpToolRequirement.Kind.READ, "find_orders", binding)))
                .isInstanceOf(McpScopeDecision.Allowed.class);
        assertThat(evaluator.evaluate(key, new McpToolRequirement(McpToolRequirement.Kind.AGENT, "ask_helper", agent)))
                .isInstanceOfSatisfying(McpScopeDecision.Denied.class,
                        d -> assertThat(d.outcome().reason()).isEqualTo(DenyReason.SCOPE_NOT_GRANTED));
    }

    @Test
    void onlyApprovedClientsAreAdmittedAndConsentIsRecordedOnce() {
        UUID approvedId = UUID.randomUUID();
        List<UUID> consents = new ArrayList<>();
        McpClientRegistryPort registry = new McpClientRegistryPort() {
            @Override
            public Optional<UUID> findApprovedClient(UUID workspaceId, String issuer, String clientId) {
                return "approved-client".equals(clientId) ? Optional.of(approvedId) : Optional.empty();
            }

            @Override
            public boolean hasActiveConsent(UUID mcpClientId, UUID principalId) {
                return consents.contains(principalId);
            }

            @Override
            public void recordConsent(UUID mcpClientId, UUID principalId, Set<String> scopes) {
                consents.add(principalId);
            }
        };
        McpClientApproval approval = new McpClientApproval(registry, IdentityExtraction.defaults(), IdentityClaimSettings.defaults());
        DaiPrincipal user = userWithScopes("dai.mcp.read");

        assertThat(approval.admit(token("approved-client"), user, WS)).isInstanceOf(McpClientApproval.Approved.class);
        assertThat(approval.admit(token("approved-client"), user, WS)).isInstanceOf(McpClientApproval.Approved.class);
        assertThat(consents).containsExactly(user.principalId());
        assertThat(approval.admit(token("rogue-client"), user, WS))
                .isEqualTo(new McpClientApproval.NotApproved("NOT_APPROVED"));
        assertThat(approval.admit(token(null), user, WS))
                .isEqualTo(new McpClientApproval.NotApproved("NO_CLIENT_ID"));
    }

    private static JwtAuthenticationToken token(String clientId) {
        Jwt.Builder builder = Jwt.withTokenValue("t-" + UUID.randomUUID()).header("alg", "RS256")
                .issuer("https://idp.example.com").subject("user-1").expiresAt(NOW.plusSeconds(600));
        if (clientId != null) {
            builder.claim("azp", clientId);
        }
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.createAuthorityList("SCOPE_dai.mcp.read"));
    }
}
