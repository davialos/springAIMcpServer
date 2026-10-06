package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.domain.Model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository;
import com.springaimcpservercommon.ruleengine.repo.TriggerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trigger points: where an integrating application asks for an evaluation — a form action (submit, approve, add,
 * buy …), optionally on one field — and the rule groups bound to it.
 */
@RestController
@RequestMapping("/api/v1/admin/triggers")
public class TriggerAdminController {

    /** A new trigger point. */
    public record TriggerRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{2,100}") String code, @NotBlank String name,
                                 @NotBlank String module, @NotBlank String form, @NotBlank String action,
                                 @Nullable String field) { }

    /** Binds a group to the trigger point. */
    public record BindingRequest(@NotBlank String group, int sequence, @Nullable Boolean active) { }

    private final TenantResolver tenants;
    private final TriggerRepository triggers;
    private final RuleRepository rules;

    public TriggerAdminController(TenantResolver tenants, TriggerRepository triggers, RuleRepository rules) {
        this.tenants = tenants;
        this.triggers = triggers;
        this.rules = rules;
    }

    @GetMapping
    public List<TriggerPoint> list(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return triggers.points(tenants.tenant(tenant).id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TriggerPoint create(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                               @Valid @RequestBody TriggerRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        Module module = tenants.module(tenant, r.module());
        return triggers.createPoint(tenant.id(), module.id(), r.code(), r.name(), r.form(), r.action(),
                r.field() == null || r.field().isBlank() ? null : r.field());
    }

    @GetMapping("/{code}/groups")
    public List<RuleGroup> groups(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                  @PathVariable String code) {
        return triggers.groupsOf(point(tenants.tenant(tenantCode), code).id());
    }

    @PutMapping("/{code}/groups")
    public List<RuleGroup> bind(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                @PathVariable String code, @Valid @RequestBody BindingRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        TriggerPoint point = point(tenant, code);
        RuleGroup group = rules.groupByCode(tenant.id(), null, r.group())
                .orElseThrow(() -> RuleEngineException.notFound("rule group " + r.group()));
        if (group.moduleId() != point.moduleId()) {
            throw RuleEngineException.badRequest("group " + r.group() + " belongs to another module than the trigger point");
        }
        triggers.bind(point.id(), group.id(), r.sequence(), r.active() == null || r.active());
        return triggers.groupsOf(point.id());
    }

    @DeleteMapping("/{code}/groups/{group}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode, @PathVariable String code,
                       @PathVariable String group) {
        Tenant tenant = tenants.tenant(tenantCode);
        TriggerPoint point = point(tenant, code);
        rules.groupByCode(tenant.id(), null, group).ifPresent(g -> triggers.unbind(point.id(), g.id()));
    }

    private TriggerPoint point(Tenant tenant, String code) {
        return triggers.pointByCode(tenant.id(), code)
                .orElseThrow(() -> RuleEngineException.notFound("trigger point " + code));
    }
}
