package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.Status;
import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.library.CelEngine;
import com.springaimcpservercommon.ruleengine.library.ContextCoercer;
import com.springaimcpservercommon.ruleengine.library.ParameterLibraryService;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository.RuleWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
import java.util.Map;

/**
 * Rules: a CEL expression over the parameter library with a message and an action for each result. An expression is
 * compiled when the rule is saved, so a typo, a non-boolean result or an attribute that is not in the library is
 * rejected (422) instead of failing at evaluation time.
 */
@RestController
@RequestMapping("/api/v1/admin/rules")
public class RuleAdminController {

    /** A rule to create or replace. Bundles are named by code. */
    public record RuleRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{2,100}") String code, @NotBlank String name,
                              @Nullable String description, @NotBlank String module, @Nullable String organization,
                              @NotBlank String expression, @Nullable String trueBundle, @Nullable String falseBundle,
                              @Nullable Action trueAction, @Nullable Action falseAction, @Nullable Status status) { }

    /** An expression to check. */
    public record ExpressionRequest(@NotBlank String expression) { }

    /** The verdict on an expression. */
    public record ExpressionCheck(boolean valid, List<String> problems, List<String> references) { }

    /** A context to try a rule on. */
    public record TestRequest(@Nullable Map<String, Map<String, Object>> context) { }

    /** What a rule gave for a context. */
    public record TestResult(@Nullable Boolean result, @Nullable String error) { }

    private final TenantResolver tenants;
    private final RuleRepository rules;
    private final BundleRepository bundles;
    private final CelEngine cel;
    private final ParameterLibraryService library;
    private final ContextCoercer coercer;

    public RuleAdminController(TenantResolver tenants, RuleRepository rules, BundleRepository bundles, CelEngine cel,
                               ParameterLibraryService library, ContextCoercer coercer) {
        this.tenants = tenants;
        this.rules = rules;
        this.bundles = bundles;
        this.cel = cel;
        this.library = library;
        this.coercer = coercer;
    }

    @GetMapping
    public List<Rule> list(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return rules.rules(tenants.tenant(tenant).id());
    }

    @GetMapping("/{id}")
    public Rule get(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant, @PathVariable long id) {
        return owned(tenants.tenant(tenant), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Rule create(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                       @Valid @RequestBody RuleRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        return rules.createRule(tenant.id(), write(tenant, r));
    }

    @PutMapping("/{id}")
    public Rule update(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode, @PathVariable long id,
                       @Valid @RequestBody RuleRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        owned(tenant, id);
        return rules.updateRule(id, write(tenant, r));
    }

    /** Checks an expression against the cached library without saving anything. */
    @PostMapping("/validate")
    public ExpressionCheck validate(@Valid @RequestBody ExpressionRequest r) {
        try {
            CelEngine.Compiled compiled = cel.compile(r.expression());
            return new ExpressionCheck(true, List.of(), List.copyOf(compiled.references()));
        } catch (CelEngine.ExpressionException e) {
            return new ExpressionCheck(false, e.problems(), List.of());
        }
    }

    /** Evaluates the saved rule on a context: the rule editor's "try it". */
    @PostMapping("/{id}/test")
    public TestResult test(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant, @PathVariable long id,
                           @RequestBody TestRequest r) {
        Rule rule = owned(tenants.tenant(tenant), id);
        Map<String, Object> variables = coercer.coerce(r.context() == null ? Map.of() : r.context(), library.current());
        try {
            return new TestResult(cel.evaluate(cel.compile(rule.expression()), variables), null);
        } catch (CelEngine.ExpressionException e) {
            return new TestResult(null, e.getMessage());
        }
    }

    private Rule owned(Tenant tenant, long id) {
        Rule rule = rules.rule(id).orElseThrow(() -> RuleEngineException.notFound("rule " + id));
        if (rule.tenantId() != tenant.id()) {
            throw RuleEngineException.notFound("rule " + id);
        }
        return rule;
    }

    private RuleWrite write(Tenant tenant, RuleRequest r) {
        Module module = tenants.module(tenant, r.module());
        Organization org = tenants.organization(tenant, r.organization());
        cel.compile(r.expression()); // rejects what cannot work: syntax, non-boolean, unknown objects or attributes
        return new RuleWrite(org == null ? null : org.id(), module.id(), r.code(), r.name(), r.description(),
                r.expression(), bundle(r.trueBundle()), bundle(r.falseBundle()),
                r.trueAction() == null ? Action.ALLOW : r.trueAction(),
                r.falseAction() == null ? Action.ALLOW : r.falseAction(),
                r.status() == null ? Status.ACTIVE : r.status());
    }

    private @Nullable Long bundle(@Nullable String code) {
        return code == null || code.isBlank() ? null
                : bundles.bundle(code).orElseThrow(() -> RuleEngineException.notFound("bundle " + code)).id();
    }
}
