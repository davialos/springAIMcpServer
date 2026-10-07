package com.springaimcpservercommon.ecosystem.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates the small set of dev tenants, organizations and users the local stack signs in with (disabled with
 * {@code ecosystem.auth.seed.enabled=false}). Idempotent: existing rows are left alone, so a changed password in
 * {@code .env} only affects a fresh database. The ids match {@code scripts/rule-engine/sample-data.sql}, so the seeded
 * loan rules belong to Acme Bank / Retail.
 *
 * <pre>
 *   admin       ADMIN  Acme Bank / Retail       sees rules and logs
 *   user        USER   Acme Bank / Retail       sees rules, creates rule groups, no logs
 *   corp.user   USER   Acme Bank / Corporate    same tenant, other organization
 *   globex.admin ADMIN Globex / (tenant-wide)   other tenant: proves isolation
 * </pre>
 */
@Component
class DevSeed implements ApplicationRunner {

    static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ACME_RETAIL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ACME_CORPORATE = UUID.fromString("22222222-2222-2222-2222-222222222223");
    static final UUID GLOBEX = UUID.fromString("11111111-1111-1111-1111-111111111112");

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthProperties props;

    DevSeed(JdbcClient jdbc, PasswordEncoder encoder, AuthProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled()) {
            return;
        }
        tenant(ACME, "acme", "Acme Bank");
        tenant(GLOBEX, "globex", "Globex Corporation");
        organization(ACME_RETAIL, ACME, "retail", "Retail");
        organization(ACME_CORPORATE, ACME, "corporate", "Corporate");
        String admin = props.seed().adminPassword();
        String user = props.seed().userPassword();
        user("33333333-0000-0000-0000-000000000001", ACME, ACME_RETAIL, "admin", "Ada Admin", admin, "ADMIN");
        user("33333333-0000-0000-0000-000000000002", ACME, ACME_RETAIL, "user", "Uma User", user, "USER");
        user("33333333-0000-0000-0000-000000000003", ACME, ACME_CORPORATE, "corp.user", "Chris Corporate", user,
                "USER");
        user("33333333-0000-0000-0000-000000000004", GLOBEX, null, "globex.admin", "Gina Globex", admin, "ADMIN");
        log.info("dev seed ready: users admin, user, corp.user, globex.admin (passwords from configuration)");
    }

    private void tenant(UUID id, String code, String name) {
        jdbc.sql("INSERT INTO re_auth_tenant (id, code, name) VALUES (:id, :code, :name) ON CONFLICT DO NOTHING")
                .param("id", id).param("code", code).param("name", name).update();
    }

    private void organization(UUID id, UUID tenant, String code, String name) {
        jdbc.sql("INSERT INTO re_auth_organization (id, tenant_id, code, name) VALUES (:id, :t, :code, :name) "
                        + "ON CONFLICT DO NOTHING")
                .param("id", id).param("t", tenant).param("code", code).param("name", name).update();
    }

    private void user(String id, UUID tenant, UUID organization, String username, String display, String password,
                      String role) {
        boolean exists = jdbc.sql("SELECT count(*) FROM re_auth_user WHERE lower(username) = lower(:u)")
                .param("u", username).query(Long.class).single() > 0;
        if (exists) {
            return;
        }
        jdbc.sql("INSERT INTO re_auth_user (id, tenant_id, organization_id, username, display_name, password_hash, role) "
                        + "VALUES (:id, :t, :o, :u, :d, :h, :r)")
                .param("id", UUID.fromString(id)).param("t", tenant).param("o", organization).param("u", username)
                .param("d", display).param("h", encoder.encode(password)).param("r", role).update();
    }
}
