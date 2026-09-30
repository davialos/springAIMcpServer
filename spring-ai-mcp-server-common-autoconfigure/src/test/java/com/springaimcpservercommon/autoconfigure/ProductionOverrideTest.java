package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.audit.AuditEventDraft;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The break-glass production override (LLD-12 §2.3, OQ-53): configuration, expiry and the audit of every use. */
class ProductionOverrideTest {

    private static String in(long hours) {
        return Instant.now().plusSeconds(hours * 3600).toString();
    }

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DaiCoreAutoConfiguration.class))
                .withPropertyValues("dynamic.ai.agent.environment.tier=PROD")
                .withPropertyValues(properties);
    }

    private static com.springaimcpservercommon.core.environment.EnvironmentIdentity identity(
            EnvironmentSafetyPolicy policy) {
        return policy.identify(new EnvironmentSignals("PROD", java.util.List.of(), null, null));
    }

    @Test
    void withoutAnOverrideProductionKeepsAuthoringOff() {
        runner().run(ctx -> {
            var policy = ctx.getBean(EnvironmentSafetyPolicy.class);
            assertThat(policy.isEnabled(Capability.AUTHORING, identity(policy))).isFalse();
            assertThat(policy.enabledByOverride(Capability.AUTHORING, identity(policy))).isFalse();
        });
    }

    @Test
    void aConfiguredOverrideEnablesOnlyItsCapabilitiesAndNeverQueryPreview() {
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(2),
                "dynamic.ai.agent.environment.production-override.reason=INC-1234").run(ctx -> {
            var policy = ctx.getBean(EnvironmentSafetyPolicy.class);
            var identity = identity(policy);
            assertThat(policy.isEnabled(Capability.AUTHORING, identity)).isTrue();
            assertThat(policy.enabledByOverride(Capability.AUTHORING, identity)).isTrue();
            assertThat(policy.isEnabled(Capability.INTROSPECTION, identity)).isFalse();
        });
    }

    @Test
    void anExpiredOverrideIsInactive() {
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(-1),
                "dynamic.ai.agent.environment.production-override.reason=INC-1").run(ctx -> {
            var policy = ctx.getBean(EnvironmentSafetyPolicy.class);
            assertThat(policy.isEnabled(Capability.AUTHORING, identity(policy))).isFalse();
        });
    }

    @Test
    void aMisconfiguredOverrideStopsTheApplication() {
        // too long, missing reason, missing expiry, a capability that can never be overridden
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(100),
                "dynamic.ai.agent.environment.production-override.reason=x")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(2))
                .run(ctx -> assertThat(ctx).hasFailed());
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.reason=x")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner("dynamic.ai.agent.environment.production-override.capabilities=QUERY_PREVIEW",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(2),
                "dynamic.ai.agent.environment.production-override.reason=x")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void everyUseOfAnOverriddenCapabilityIsAuditedAndOrdinaryUseIsNot() {
        AuditTrail trail = Mockito.mock(AuditTrail.class);
        DaiPrincipal caller = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice", "Alice",
                Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());
        runner("dynamic.ai.agent.environment.production-override.capabilities=AUTHORING",
                "dynamic.ai.agent.environment.production-override.expires-at=" + in(2),
                "dynamic.ai.agent.environment.production-override.reason=INC-1234").run(ctx -> {
            var policy = ctx.getBean(EnvironmentSafetyPolicy.class);
            var api = new AdminApi(request -> caller, Mockito.mock(com.springaimcpservercommon.security.authz.AuthorizationEngine.class), policy,
                    new EnvironmentSignals("PROD", java.util.List.of(), null, null), new AdminAudit(trail));
            var request = new MockHttpServletRequest("POST", "/dynamic-ai/admin/api/v1/workspaces");

            assertThat(api.capabilityDenied(Capability.AUTHORING, request)).isNull();
            ArgumentCaptor<AuditEventDraft> draft = ArgumentCaptor.forClass(AuditEventDraft.class);
            Mockito.verify(trail).append(draft.capture());
            assertThat(draft.getValue().action()).isEqualTo("PRODUCTION_OVERRIDE_USED");
            assertThat(draft.getValue().resourceId()).isEqualTo("AUTHORING");

            // a capability the override does not list stays refused and is not audited as used
            assertThat(api.capabilityDenied(Capability.INTROSPECTION, request)).isNotNull();
            // a capability that is always on is not an override
            assertThat(api.capabilityDenied(Capability.DATA_PLANE, request)).isNull();
            Mockito.verifyNoMoreInteractions(trail);
        });
    }
}
