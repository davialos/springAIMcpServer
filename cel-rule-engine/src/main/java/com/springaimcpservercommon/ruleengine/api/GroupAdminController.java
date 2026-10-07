package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.CompositeMode;
import com.springaimcpservercommon.ruleengine.domain.Enums.OnError;
import com.springaimcpservercommon.ruleengine.domain.Enums.Status;
import com.springaimcpservercommon.ruleengine.domain.Model.GroupMember;
import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.domain.Policy;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository.GroupWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * Rule groups per tenant (and optionally organization) and module: the rules of a group with their sequence, the
 * evaluation policy and the group's own messages and actions.
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class GroupAdminController {

    /**
     * A rule group to create or replace. {@code matchOn} (TRUE/FALSE) is what FIRST_MATCH and ALL_MATCH look for;
     * {@code compositeMode} how COMPOSITE combines; {@code onError} what an unevaluable rule counts as.
     */
    public record GroupRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{2,100}") String code, @NotBlank String name,
                               @Nullable String description, @NotBlank String module, @Nullable String organization,
                               @NotNull Policy policy, @Nullable Boolean matchOnTrue,
                               @Nullable CompositeMode compositeMode, @Nullable OnError onError,
                               @Nullable String trueBundle, @Nullable String falseBundle, @Nullable Action trueAction,
                               @Nullable Action falseAction, @Nullable Status status) { }

    /** Puts a rule into the group, or moves it. */
    public record MemberRequest(@NotBlank String rule, int sequence, @Nullable Boolean active) { }

    /** A group's rule with its place. */
    public record MemberView(String rule, int sequence, boolean active) { }

    private final TenantResolver tenants;
    private final RuleRepository rules;
    private final BundleRepository bundles;

    public GroupAdminController(TenantResolver tenants, RuleRepository rules, BundleRepository bundles) {
        this.tenants = tenants;
        this.rules = rules;
        this.bundles = bundles;
    }

    @GetMapping
    public List<RuleGroup> list(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return rules.groups(tenants.tenant(tenant).id());
    }

    @GetMapping("/{id}")
    public RuleGroup get(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant, @PathVariable long id) {
        return owned(tenants.tenant(tenant), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleGroup create(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                            @Valid @RequestBody GroupRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        return rules.createGroup(tenant.id(), write(tenant, r));
    }

    @PutMapping("/{id}")
    public RuleGroup update(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode, @PathVariable long id,
                            @Valid @RequestBody GroupRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        owned(tenant, id);
        return rules.updateGroup(id, write(tenant, r));
    }

    @GetMapping("/{id}/members")
    public List<MemberView> members(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant, @PathVariable long id) {
        owned(tenants.tenant(tenant), id);
        return rules.members(id).stream().map(this::view).toList();
    }

    @PutMapping("/{id}/members")
    public List<MemberView> setMember(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                      @PathVariable long id, @Valid @RequestBody MemberRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        RuleGroup group = owned(tenant, id);
        Rule rule = rules.rules(tenant.id()).stream().filter(x -> x.code().equals(r.rule())
                && (x.organizationId() == null || x.organizationId().equals(group.organizationId()))).findFirst()
                .orElseThrow(() -> RuleEngineException.notFound("rule " + r.rule()));
        if (rule.moduleId() != group.moduleId()) {
            throw RuleEngineException.badRequest("rule " + r.rule() + " belongs to another module than the group");
        }
        rules.setMember(id, rule.id(), r.sequence(), r.active() == null || r.active());
        return rules.members(id).stream().map(this::view).toList();
    }

    @DeleteMapping("/{id}/members/{rule}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode, @PathVariable long id,
                             @PathVariable String rule) {
        Tenant tenant = tenants.tenant(tenantCode);
        owned(tenant, id);
        rules.rules(tenant.id()).stream().filter(x -> x.code().equals(rule))
                .forEach(x -> rules.removeMember(id, x.id()));
    }

    private MemberView view(GroupMember m) {
        Rule rule = rules.rule(m.ruleId()).orElseThrow();
        return new MemberView(rule.code(), m.sequence(), m.active());
    }

    private RuleGroup owned(Tenant tenant, long id) {
        RuleGroup g = rules.group(id).orElseThrow(() -> RuleEngineException.notFound("rule group " + id));
        if (g.tenantId() != tenant.id()) {
            throw RuleEngineException.notFound("rule group " + id);
        }
        return g;
    }

    private GroupWrite write(Tenant tenant, GroupRequest r) {
        Module module = tenants.module(tenant, r.module());
        Organization org = tenants.organization(tenant, r.organization());
        return new GroupWrite(org == null ? null : org.id(), module.id(), r.code(), r.name(), r.description(),
                r.policy(), r.matchOnTrue() == null || r.matchOnTrue(),
                r.compositeMode() == null ? CompositeMode.ALL_TRUE : r.compositeMode(),
                r.onError() == null ? OnError.AS_FALSE : r.onError(), bundle(r.trueBundle()), bundle(r.falseBundle()),
                r.trueAction() == null ? Action.ALLOW : r.trueAction(),
                r.falseAction() == null ? Action.ALLOW : r.falseAction(),
                r.status() == null ? Status.ACTIVE : r.status());
    }

    private @Nullable Long bundle(@Nullable String code) {
        return code == null || code.isBlank() ? null
                : bundles.bundle(code).orElseThrow(() -> RuleEngineException.notFound("bundle " + code)).id();
    }
}
