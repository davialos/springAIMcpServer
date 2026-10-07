package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.eval.EvaluateRequest;
import com.springaimcpservercommon.ruleengine.eval.EvaluationService;
import com.springaimcpservercommon.ruleengine.eval.Results.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.repo.LogRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The endpoint integrating applications call from a trigger point. */
@RestController
@RequestMapping("/api/v1")
public class EvaluationController {

    private final TenantResolver tenants;
    private final EvaluationService evaluation;
    private final LogRepository logs;

    public EvaluationController(TenantResolver tenants, EvaluationService evaluation, LogRepository logs) {
        this.tenants = tenants;
        this.evaluation = evaluation;
        this.logs = logs;
    }

    /**
     * Evaluates the rule groups of a trigger point (a form action, optionally one field) or of the groups named, and
     * answers with the final messages, the action (ALLOW, WARN, BLOCK) and, when {@code detailed}, every rule's raw result.
     */
    @PostMapping("/evaluate")
    public EvaluationResponse evaluate(
            @RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
            @RequestHeader(value = TenantResolver.ORGANIZATION_HEADER, required = false) String organizationCode,
            @Valid @RequestBody EvaluateRequest request) {
        Tenant tenant = tenants.tenant(tenantCode);
        Organization organization = tenants.organization(tenant, organizationCode);
        return evaluation.evaluate(tenant, organization, request);
    }

    /** The audit trail: the latest evaluations of the tenant. */
    @GetMapping("/evaluations")
    public List<LogRepository.EvaluationRow> latest(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return logs.evaluations(tenants.tenant(tenantCode).id(), Math.min(Math.max(limit, 1), 500));
    }

    /** What happened to the channels of one evaluation. */
    @GetMapping("/evaluations/{id}/dispatches")
    public List<LogRepository.DispatchRow> dispatches(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return logs.dispatches(id);
    }
}
