package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.repo.CatalogRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tenants, organizations, modules and the modules a tenant has selected. */
@RestController
@RequestMapping("/api/v1/admin")
public class MasterAdminController {

    /** A new tenant. */
    public record TenantRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String code, @NotBlank String name,
                                @Nullable String defaultLanguage) { }

    /** A new organization (of the tenant in the header). */
    public record OrganizationRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String code,
                                      @NotBlank String name, @Nullable String parent) { }

    /** A new module. */
    public record ModuleRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String code, @NotBlank String name,
                                @Nullable String description) { }

    /** Selects or deselects a module for the tenant in the header. */
    public record TenantModuleRequest(@NotBlank String module, boolean enabled) { }

    private final CatalogRepository catalog;
    private final TenantResolver tenants;

    public MasterAdminController(CatalogRepository catalog, TenantResolver tenants) {
        this.catalog = catalog;
        this.tenants = tenants;
    }

    @GetMapping("/tenants")
    public List<Tenant> tenants() {
        return catalog.tenants();
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Tenant createTenant(@Valid @RequestBody TenantRequest r) {
        String language = r.defaultLanguage() == null ? "en" : r.defaultLanguage();
        if (!catalog.languageExists(language)) {
            throw RuleEngineException.badRequest("unknown language " + language);
        }
        return catalog.createTenant(r.code(), r.name(), language);
    }

    @GetMapping("/organizations")
    public List<Organization> organizations(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return catalog.organizations(tenants.tenant(tenant).id());
    }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Organization createOrganization(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                           @Valid @RequestBody OrganizationRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        Long parent = r.parent() == null ? null : catalog.organization(tenant.id(), r.parent())
                .orElseThrow(() -> RuleEngineException.notFound("organization " + r.parent())).id();
        return catalog.createOrganization(tenant.id(), r.code(), r.name(), parent);
    }

    @GetMapping("/modules")
    public List<Module> modules() {
        return catalog.modules();
    }

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public Module createModule(@Valid @RequestBody ModuleRequest r) {
        return catalog.createModule(r.code(), r.name(), r.description());
    }

    /** The modules the tenant in the header has selected. */
    @GetMapping("/tenant-modules")
    public List<Module> tenantModules(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return catalog.tenantModules(tenants.tenant(tenant).id());
    }

    @PutMapping("/tenant-modules")
    public List<Module> setTenantModule(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                        @Valid @RequestBody TenantModuleRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        Module module = catalog.module(r.module()).orElseThrow(() -> RuleEngineException.notFound("module " + r.module()));
        catalog.setTenantModule(tenant.id(), module.id(), r.enabled());
        return catalog.tenantModules(tenant.id());
    }

    @GetMapping("/languages")
    public List<String> languages() {
        return catalog.languages();
    }
}
