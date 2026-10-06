package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.ruleengine.admin.AdminException;
import com.springaimcpservercommon.ruleengine.admin.ExpressionTester;
import com.springaimcpservercommon.ruleengine.admin.Revision.Kind;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The gates in front of the rule-engine authoring services, and the mapping of their refusals to problems. */
class RuleAdminControllersTest {

    private final AdminApi api = mock(AdminApi.class);
    private final AdminAudit audit = mock(AdminAudit.class);
    private final DaiPrincipal caller = mock(DaiPrincipal.class);
    private final RuleLifecycle lifecycle = mock(RuleLifecycle.class);
    private final RuleConfigAdmin config = mock(RuleConfigAdmin.class);
    private final HttpServletRequest request = new MockHttpServletRequest("POST", "/x");
    private final UUID ws = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    private RuleLifecycleAdminController lifecycleController() {
        return new RuleLifecycleAdminController(lifecycle, mock(ExpressionTester.class), new RuleAdminSupport(api, audit));
    }

    private ResponseEntity<String> problem(ProblemCode code) {
        return AdminApi.problem(code, "t", null, request);
    }

    @Test
    void aCallerWithoutThePermissionNeverReachesTheService() {
        when(api.gate(any(), eq(Permission.RULES_PUBLISH), eq(ws))).thenReturn(new AdminApi.Gate(null, problem(ProblemCode.ACCESS_DENIED)));

        ResponseEntity<?> response = lifecycleController().publish(ws, "rules", id, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(lifecycle, audit);
    }

    @Test
    void writesAreRefusedWhereTheEnvironmentDisablesAuthoringEvenForAPermittedCaller() {
        when(api.gate(any(), eq(Permission.RULES_PUBLISH), eq(ws))).thenReturn(new AdminApi.Gate(caller, null));
        when(api.capabilityDenied(eq(Capability.AUTHORING), any())).thenReturn(problem(ProblemCode.CAPABILITY_DISABLED));

        ResponseEntity<?> response = lifecycleController().publish(ws, "rules", id, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(lifecycle, never()).publish(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void readsDoNotNeedTheAuthoringCapability() {
        when(api.gate(any(), eq(Permission.RULES_READ), eq(ws))).thenReturn(new AdminApi.Gate(caller, null));
        when(lifecycle.listRules(ws, null, null)).thenReturn(List.of());

        assertThat(lifecycleController().listRules(ws, null, null, request).getStatusCode().value()).isEqualTo(200);
        verify(api, never()).capabilityDenied(any(), any());
    }

    @Test
    void aPublishIsAuditedWithTheActorAndTheRevision() {
        when(caller.principalId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        when(api.gate(any(), eq(Permission.RULES_PUBLISH), eq(ws))).thenReturn(new AdminApi.Gate(caller, null));
        var revision = new com.springaimcpservercommon.ruleengine.admin.Revision(UUID.randomUUID(), Kind.RULE, id, 3, "PUBLISHED",
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), null, null, "a",
                java.time.Instant.now(), null, null, null, null, null);
        when(lifecycle.publish(Kind.RULE, ws, id, "00000000-0000-0000-0000-0000000000aa")).thenReturn(revision);

        assertThat(lifecycleController().publish(ws, "rules", id, request).getStatusCode().value()).isEqualTo(200);

        verify(audit).record(eq(caller), eq(com.springaimcpservercommon.persistence.audit.AuditCategory.ADMIN),
                eq(com.springaimcpservercommon.persistence.audit.AuditPlane.CONTROL), eq("RULE_PUBLISHED"), eq(ws), eq("rule"),
                eq(id.toString()), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                eq(java.util.Map.of("revision", 3, "state", "PUBLISHED")));
    }

    @Test
    void theLibraryNeedsTheGlobalPermissionAndWorkspaceRolesNeverHoldIt() {
        when(api.gate(any(), eq(Permission.RULES_LIBRARY), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AdminApi.Gate(null, problem(ProblemCode.ACCESS_DENIED)));
        var controller = new RuleLibraryAdminController(config, new RuleAdminSupport(api, audit));

        assertThat(controller.library(request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.createModule(new RuleLibraryAdminController.Module("X", "x", null), request).getStatusCode().value())
                .isEqualTo(403);
        verifyNoInteractions(config);
    }

    @Test
    void refusalsBecomeProblemsWithTheStableCodeAsTitle() {
        var handler = new RuleAdminExceptionHandler();

        var notFound = handler.refused(new AdminException.NotFound("rule"), (HttpServletRequest) request);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);

        var conflict = handler.refused(new AdminException.Conflict("four_eyes", "the submitter cannot review", List.of()), request);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).contains("four_eyes");

        var inUse = handler.refused(new AdminException.Conflict("rule_in_use", "in groups", List.of("G1", "G2")), request);
        assertThat(inUse.getBody()).contains("G1, G2");

        var invalid = handler.refused(new AdminException.Invalid("invalid_rule",
                List.of("expression: undeclared reference to 'x'", "name: required")), request);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody()).contains("expression").contains("name");
    }

    @Test
    void theOutboxRetryOfAnUnknownDeliveryIsA404AndNotAudited() {
        OutboxStore outbox = mock(OutboxStore.class);
        when(api.gate(any(), eq(Permission.RULES_PUBLISH), eq(ws))).thenReturn(new AdminApi.Gate(caller, null));
        when(outbox.retry(ws, id)).thenReturn(false);

        var controller = new RuleConfigAdminController(config, outbox, new RuleAdminSupport(api, audit));

        assertThat(controller.retryDelivery(ws, id, request).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(audit);
    }
}
