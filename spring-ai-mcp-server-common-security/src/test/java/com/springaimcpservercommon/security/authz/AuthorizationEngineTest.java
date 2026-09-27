package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.TestFixtures;
import com.springaimcpservercommon.security.TestFixtures.InMemoryDirectory;
import com.springaimcpservercommon.security.TestFixtures.InMemoryGrants;
import com.springaimcpservercommon.security.TestFixtures.InMemoryKillSwitches;
import com.springaimcpservercommon.security.TestFixtures.InMemoryResourceStatus;
import com.springaimcpservercommon.security.TestFixtures.MutableClock;
import com.springaimcpservercommon.security.TestFixtures.RecordingAudit;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome.Deny;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome.Permit;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.ResourceStatusView.ResourceStatus;
import com.springaimcpservercommon.security.principal.GroupPrincipalIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z"); // 12:00 in Berlin
    private static final UUID WS = UUID.fromString("0190a000-0000-7000-8000-00000000aaaa");

    private final MutableClock clock = new MutableClock(NOW);
    private InMemoryDirectory directory;
    private InMemoryGrants grants;
    private InMemoryKillSwitches killSwitches;
    private InMemoryResourceStatus status;
    private RecordingAudit audit;
    private AuthorizationEngine engine;
    private ResourceRef agent;
    private DaiPrincipal user;

    @BeforeEach
    void setUp() {
        directory = new InMemoryDirectory();
        grants = new InMemoryGrants();
        killSwitches = new InMemoryKillSwitches();
        status = new InMemoryResourceStatus();
        audit = new RecordingAudit();
        engine = engine(ClassificationPolicy.DENY);
        agent = new ResourceRef(WS, UUID.randomUUID(), "AGENT", "sales-assistant", Classification.INTERNAL);
        user = TestFixtures.user(UUID.randomUUID(), Set.of("team-a"), Set.of(), Map.of(),
                Map.of("region", "EU"), Classification.INTERNAL, Set.of());
    }

    private AuthorizationEngine engine(ClassificationPolicy policy) {
        return AuthorizationEngine.builder(grants, killSwitches, status,
                        new GroupPrincipalIds(directory, clock, Duration.ofMinutes(1), 100))
                .environment(Map.of("tier", "PROD"))
                .classificationPolicy(policy)
                .auditListeners(List.of(audit))
                .clock(clock)
                .build();
    }

    private AuthorizationOutcome invoke(DaiPrincipal principal, ResourceRef resource) {
        return engine.decide(AuthorizationRequest.onResource(principal, Permission.AGENT_INVOKE, resource));
    }

    @Test
    void defaultDenyWithoutGrant() {
        assertThat(invoke(user, agent)).isInstanceOfSatisfying(Deny.class,
                d -> assertThat(d.reason()).isEqualTo(DenyReason.NO_MATCHING_GRANT));
    }

    @Test
    void grantToAGroupPrincipalPermitsAndIsAudited() {
        UUID group = directory.group(user.issuer(), "team-a");
        GrantSource.GrantRecord grant = grants.add(WS, group, "agent:invoke", agent.resourceId(), null, null, null);

        assertThat(invoke(user, agent)).isInstanceOfSatisfying(Permit.class, p -> {
            assertThat(p.grantId()).isEqualTo(grant.id());
            assertThat(p.via()).isEqualTo(AuthorizationOutcome.Via.GRANT);
        });
        assertThat(audit.events).singleElement().satisfies(e -> {
            assertThat(e.principalId()).isEqualTo(user.principalId());
            assertThat(e.resourceId()).isEqualTo(agent.resourceId());
            assertThat(e.outcome().granted()).isTrue();
        });
    }

    @Test
    void killSwitchWinsOverGrants() {
        grants.add(WS, user.principalId(), "agent:invoke", null, null, null, null);
        UUID switchId = UUID.randomUUID();
        killSwitches.byResource.put(agent.resourceId(), new KillSwitchView.ActiveKillSwitch(switchId, KillSwitchView.Scope.RESOURCE));
        assertThat(invoke(user, agent)).isEqualTo(new Deny(Permission.AGENT_INVOKE, DenyReason.KILL_SWITCH, switchId));
    }

    @Test
    void unpublishedAndSuspendedResourcesAreDenied() {
        grants.add(WS, user.principalId(), "agent:invoke", null, null, null, null);
        status.states.put(agent.resourceId(), ResourceStatus.NOT_PUBLISHED);
        assertThat(((Deny) invoke(user, agent)).reason()).isEqualTo(DenyReason.RESOURCE_NOT_PUBLISHED);
        assertThat(engine.decide(AuthorizationRequest.onResource(user, Permission.AGENT_INVOKE, agent).withDraftAccess()).granted())
                .isTrue();
        status.states.put(agent.resourceId(), ResourceStatus.SUSPENDED);
        assertThat(((Deny) invoke(user, agent)).reason()).isEqualTo(DenyReason.RESOURCE_SUSPENDED);
    }

    @Test
    void expiredGrantsAndOtherTargetsDoNotMatch() {
        grants.add(WS, user.principalId(), "agent:invoke", null, null, null, NOW.minusSeconds(1));
        grants.add(WS, user.principalId(), "agent:invoke", UUID.randomUUID(), null, null, null);
        grants.add(WS, user.principalId(), "agent:invoke", null, "agent/billing-*", null, null);
        assertThat(invoke(user, agent).granted()).isFalse();

        grants.add(WS, user.principalId(), "agent:invoke", null, "agent/sales-*", null, null);
        assertThat(invoke(user, agent).granted()).isTrue();
    }

    @Test
    void abacConditionsDecideAndInvalidConditionsFailClosed() {
        grants.add(WS, user.principalId(), "agent:invoke", null, null,
                "{\"principal.region\":{\"in\":[\"US\"]}}", null);
        assertThat(((Deny) invoke(user, agent)).reason()).isEqualTo(DenyReason.CONDITION_NOT_MET);

        grants.grants.clear();
        grants.add(WS, user.principalId(), "agent:invoke", null, null, "{\"principal.region\":{\"regex\":\".*\"}}", null);
        assertThat(((Deny) invoke(user, agent)).reason()).isEqualTo(DenyReason.INVALID_CONDITION);

        grants.add(WS, user.principalId(), "agent:invoke", agent.resourceId(), null,
                "{\"principal.region\":{\"in\":[\"EU\"]},\"environment.tier\":{\"eq\":\"PROD\"},"
                        + "\"time\":{\"between\":[\"08:00\",\"18:00\"],\"zone\":\"Europe/Berlin\"}}", null);
        assertThat(invoke(user, agent).granted()).isTrue();

        clock.set(Instant.parse("2026-09-28T20:00:00Z")); // 22:00 in Berlin
        assertThat(invoke(user, agent).granted()).isFalse();
    }

    @Test
    void classificationAboveClearanceIsDeniedOrMasked() {
        grants.add(WS, user.principalId(), "agent:invoke", null, null, null, null);
        ResourceRef restricted = new ResourceRef(WS, UUID.randomUUID(), "AGENT", "hr", Classification.RESTRICTED);
        assertThat(((Deny) invoke(user, restricted)).reason()).isEqualTo(DenyReason.CLASSIFICATION);

        AuthorizationOutcome masked = engine(ClassificationPolicy.MASK)
                .decide(AuthorizationRequest.onResource(user, Permission.AGENT_INVOKE, restricted));
        assertThat(masked).isInstanceOfSatisfying(Permit.class, p -> assertThat(p.maskingRequired()).isTrue());
    }

    @Test
    void controlPlanePermissionsComeFromRoleBundlesPerWorkspace() {
        DaiPrincipal author = TestFixtures.user(UUID.randomUUID(), Set.of(), Set.of(),
                Map.of(WS, Set.of(FrameworkRole.AUTHOR)), Map.of(), Classification.INTERNAL, Set.of());
        assertThat(engine.decide(AuthorizationRequest.onWorkspace(author, Permission.ENDPOINT_AUTHOR, WS)))
                .isInstanceOfSatisfying(Permit.class, p -> assertThat(p.role()).isEqualTo(FrameworkRole.AUTHOR));
        assertThat(engine.decide(AuthorizationRequest.onWorkspace(author, Permission.ENDPOINT_AUTHOR, UUID.randomUUID())).granted())
                .isFalse();
        assertThat(engine.decide(AuthorizationRequest.global(author, Permission.ENDPOINT_AUTHOR)).granted()).isFalse();
        // kill switches do not lock administrators out of the control plane
        killSwitches.global = new KillSwitchView.ActiveKillSwitch(UUID.randomUUID(), KillSwitchView.Scope.GLOBAL);
        assertThat(engine.decide(AuthorizationRequest.onWorkspace(author, Permission.ENDPOINT_AUTHOR, WS)).granted()).isTrue();
    }

    @Test
    void apiKeyServiceAccountsAreLimitedToKeyScopes() {
        UUID saPrincipal = UUID.randomUUID();
        grants.add(WS, saPrincipal, "agent:invoke", null, null, null, null);
        DaiPrincipal withoutScope = new DaiPrincipal(saPrincipal, SubjectType.SERVICE_ACCOUNT, "dai", "sa-1", "bot",
                Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of("tool:invoke"));
        assertThat(((Deny) invoke(withoutScope, agent)).reason()).isEqualTo(DenyReason.SCOPE_NOT_GRANTED);

        DaiPrincipal withScope = new DaiPrincipal(saPrincipal, SubjectType.SERVICE_ACCOUNT, "dai", "sa-1", "bot",
                Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of("agent:invoke"));
        assertThat(invoke(withScope, agent).granted()).isTrue();
    }

    @Test
    void portFailuresDenyWithInternalError() {
        AuthorizationEngine broken = AuthorizationEngine.builder((ws, ids, p) -> {
                    throw new IllegalStateException("db down");
                }, killSwitches, status, new GroupPrincipalIds(directory, clock, Duration.ofMinutes(1), 10))
                .auditListeners(List.of(audit)).clock(clock).build();
        AuthorizationOutcome outcome = broken.decide(AuthorizationRequest.onResource(user, Permission.AGENT_INVOKE, agent));
        assertThat(((Deny) outcome).reason()).isEqualTo(DenyReason.INTERNAL_ERROR);
        assertThat(audit.events).hasSize(1);
    }

    @Test
    void springAdapterAndDaiAuthzHelperDenyUnauthenticatedCallers() {
        DaiAuthorizationManager manager = new DaiAuthorizationManager(auth -> user, engine);
        grants.add(WS, user.principalId(), "agent:invoke", null, null, null, null);

        var authenticated = new TestingAuthenticationToken("u", "p", "ROLE_USER");
        authenticated.setAuthenticated(true);
        assertThat(manager.authorize(() -> authenticated, InvocationTarget.resource(Permission.AGENT_INVOKE, agent)).isGranted())
                .isTrue();
        assertThat(manager.authorize(() -> null, InvocationTarget.resource(Permission.AGENT_INVOKE, agent)))
                .isInstanceOfSatisfying(DaiAuthorizationDecision.class,
                        d -> assertThat(((Deny) d.outcome()).reason()).isEqualTo(DenyReason.NOT_AUTHENTICATED));

        DaiAuthorization daiAuthz = new DaiAuthorization(manager);
        assertThat(daiAuthz.can(authenticated, "endpoint:author", WS.toString())).isFalse();
        assertThat(daiAuthz.can(authenticated, "no:such", WS)).isFalse();
        assertThat(daiAuthz.can(authenticated, "endpoint:author", "not-a-uuid")).isFalse();
    }
}
