package com.springaimcpservercommon.ruleengine.repo;

import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenants, organizations, modules and languages. */
@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> tenant(String code) {
        return jdbc.sql("select id, code, name, default_language, active from re_tenant where code = :c")
                .param("c", code).query(Tenant.class).optional();
    }

    public List<Tenant> tenants() {
        return jdbc.sql("select id, code, name, default_language, active from re_tenant order by code")
                .query(Tenant.class).list();
    }

    public Tenant createTenant(String code, String name, String defaultLanguage) {
        long id = jdbc.sql("insert into re_tenant (code, name, default_language) values (:c, :n, :l) returning id")
                .param("c", code).param("n", name).param("l", defaultLanguage).query(Long.class).single();
        return new Tenant(id, code, name, defaultLanguage, true);
    }

    public Optional<Organization> organization(long tenantId, String code) {
        return jdbc.sql("select id, tenant_id, parent_id, code, name, active from re_organization "
                        + "where tenant_id = :t and code = :c")
                .param("t", tenantId).param("c", code).query(Organization.class).optional();
    }

    public List<Organization> organizations(long tenantId) {
        return jdbc.sql("select id, tenant_id, parent_id, code, name, active from re_organization "
                        + "where tenant_id = :t order by code")
                .param("t", tenantId).query(Organization.class).list();
    }

    public Organization createOrganization(long tenantId, String code, String name, Long parentId) {
        long id = jdbc.sql("insert into re_organization (tenant_id, parent_id, code, name) values (:t, :p, :c, :n) "
                        + "returning id")
                .param("t", tenantId).param("p", parentId).param("c", code).param("n", name)
                .query(Long.class).single();
        return new Organization(id, tenantId, parentId, code, name, true);
    }

    public Optional<Module> module(String code) {
        return jdbc.sql("select id, code, name, description, active from re_module where code = :c")
                .param("c", code).query(Module.class).optional();
    }

    public Optional<Module> module(long id) {
        return jdbc.sql("select id, code, name, description, active from re_module where id = :i")
                .param("i", id).query(Module.class).optional();
    }

    public List<Module> modules() {
        return jdbc.sql("select id, code, name, description, active from re_module order by code")
                .query(Module.class).list();
    }

    public Module createModule(String code, String name, String description) {
        long id = jdbc.sql("insert into re_module (code, name, description) values (:c, :n, :d) returning id")
                .param("c", code).param("n", name).param("d", description).query(Long.class).single();
        return new Module(id, code, name, description, true);
    }

    public boolean tenantHasModule(long tenantId, long moduleId) {
        return jdbc.sql("select count(*) from re_tenant_module where tenant_id = :t and module_id = :m and enabled")
                .param("t", tenantId).param("m", moduleId).query(Long.class).single() > 0;
    }

    public void setTenantModule(long tenantId, long moduleId, boolean enabled) {
        jdbc.sql("insert into re_tenant_module (tenant_id, module_id, enabled) values (:t, :m, :e) "
                        + "on conflict (tenant_id, module_id) do update set enabled = :e")
                .param("t", tenantId).param("m", moduleId).param("e", enabled).update();
    }

    public List<Module> tenantModules(long tenantId) {
        return jdbc.sql("select m.id, m.code, m.name, m.description, m.active from re_module m "
                        + "join re_tenant_module tm on tm.module_id = m.id where tm.tenant_id = :t and tm.enabled "
                        + "order by m.code")
                .param("t", tenantId).query(Module.class).list();
    }

    public boolean languageExists(String code) {
        return jdbc.sql("select count(*) from sys_language where code = :c").param("c", code)
                .query(Long.class).single() > 0;
    }

    public List<String> languages() {
        return jdbc.sql("select code from sys_language order by code").query(String.class).list();
    }
}
