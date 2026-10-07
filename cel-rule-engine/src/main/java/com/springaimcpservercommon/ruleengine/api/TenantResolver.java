package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.repo.CatalogRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resolves the tenant (and organization) a request is made for from the {@code X-Tenant-Code} headers. */
@Component
public class TenantResolver {

    /** Header naming the tenant. */
    public static final String TENANT_HEADER = "X-Tenant-Code";
    /** Header naming the organization (optional). */
    public static final String ORGANIZATION_HEADER = "X-Organization-Code";

    private final CatalogRepository catalog;

    public TenantResolver(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public Tenant tenant(@Nullable String code) {
        if (code == null || code.isBlank()) {
            throw RuleEngineException.badRequest("the " + TENANT_HEADER + " header is required");
        }
        Tenant tenant = catalog.tenant(code).orElseThrow(() -> RuleEngineException.notFound("tenant " + code));
        if (!tenant.active()) {
            throw new RuleEngineException(HttpStatus.FORBIDDEN, "TENANT_INACTIVE", "tenant " + code + " is inactive");
        }
        return tenant;
    }

    public @Nullable Organization organization(Tenant tenant, @Nullable String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return catalog.organization(tenant.id(), code)
                .orElseThrow(() -> RuleEngineException.notFound("organization " + code));
    }

    /** A module the tenant has selected. */
    public Module module(Tenant tenant, String code) {
        Module module = catalog.module(code).orElseThrow(() -> RuleEngineException.notFound("module " + code));
        if (!catalog.tenantHasModule(tenant.id(), module.id())) {
            throw new RuleEngineException(HttpStatus.FORBIDDEN, "MODULE_NOT_ENABLED",
                    "tenant " + tenant.code() + " has not selected module " + code);
        }
        return module;
    }
}
