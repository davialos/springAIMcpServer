package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Plumbing shared by the rule-engine admin controllers: the permission gate, the environment capability gate for
 * writes (rule authoring is off in PROD unless the audited production override is active, LLD-12) and audit records.
 * In the admin API the workspace is the rule engine's tenant.
 */
@NullMarked
final class RuleAdminSupport {

    private final AdminApi api;
    private final AdminAudit audit;

    RuleAdminSupport(AdminApi api, AdminAudit audit) {
        this.api = Objects.requireNonNull(api, "api");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** A read: permission only. */
    AdminApi.Gate read(HttpServletRequest request, Permission permission, @Nullable UUID workspaceId) {
        return api.gate(request, permission, workspaceId);
    }

    /** A write: permission, then the AUTHORING capability of the environment. */
    AdminApi.Gate write(HttpServletRequest request, Permission permission, @Nullable UUID workspaceId) {
        AdminApi.Gate gate = api.gate(request, permission, workspaceId);
        if (!gate.open()) {
            return gate;
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.AUTHORING, request);
        return disabled == null ? gate : new AdminApi.Gate(null, disabled);
    }

    /** The id recorded as author/reviewer/publisher. */
    static String actor(AdminApi.Gate gate) {
        return gate.caller().principalId().toString();
    }

    void audit(AdminApi.Gate gate, String action, @Nullable UUID workspaceId, String resourceType, String resourceId,
               @Nullable String reason, Map<String, Object> details) {
        DaiPrincipal caller = gate.caller();
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, workspaceId, resourceType, resourceId, null,
                reason, details);
    }

    void audit(AdminApi.Gate gate, String action, @Nullable UUID workspaceId, String resourceType, String resourceId) {
        audit(gate, action, workspaceId, resourceType, resourceId, null, new LinkedHashMap<>());
    }
}
