package com.springaimcpservercommon.ecosystem.ruleengine;

import com.nimbusds.jwt.JWTClaimsSet;
import com.springaimcpservercommon.ecosystem.ruleengine.Api.Persona;
import com.springaimcpservercommon.ecosystem.ruleengine.Api.Resp;
import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule-engine service end to end: the library's real migrations (V1..V12), the real sample data, real signed tokens
 * and real HTTP, against PostgreSQL. Uses Testcontainers; set {@code ECOSYSTEM_IT_JDBC_URL}
 * (+ {@code ECOSYSTEM_IT_USER}/{@code ECOSYSTEM_IT_PASSWORD}) to use an existing database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleEngineServiceIT {

    private static final Path SAMPLE = Path.of("../../../scripts/rule-engine/sample-data.sql");
    private static final String SENTINEL = "SENTINEL-NEVER-STORED-7f3a91";

    private static PostgreSQLContainer container;
    private static String url;
    private static String user;
    private static String password;
    private static boolean seeded;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        url = System.getenv("ECOSYSTEM_IT_JDBC_URL");
        if (url == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } else {
            user = System.getenv("ECOSYSTEM_IT_USER");
            password = System.getenv("ECOSYSTEM_IT_PASSWORD");
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS dynamic_ai CASCADE");
        }
        String pw = password == null ? "" : password;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pw);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> pw);
        registry.add("ecosystem.rules.jwt-secret", () -> Api.SECRET);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    Api api;

    @BeforeEach
    void seed() throws SQLException, IOException {
        api = new Api(port);
        if (seeded) {
            return;
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("SET search_path TO dynamic_ai");
            st.execute(Files.readString(SAMPLE));
            // an AI chat conversation of the Acme tenant (the library's own tables), for the admin chat log
            st.execute("""
                    INSERT INTO dai_principal (id, subject_type, issuer, external_id, display_name)
                    VALUES ('99999999-0000-0000-0000-000000000001', 'USER', 'it', 'it-user', 'IT User');
                    INSERT INTO dai_workspace (id, slug, name, tenant_id)
                    VALUES ('99999999-0000-0000-0000-000000000002', 'acme-ws', 'Acme workspace', '%s');
                    INSERT INTO dai_conversation (id, workspace_id, principal_id, channel, conversation_key_hash, title,
                                                  retention_until)
                    VALUES ('99999999-0000-0000-0000-000000000003', '99999999-0000-0000-0000-000000000002',
                            '99999999-0000-0000-0000-000000000001', 'CHAT', 'hash-1', 'Why was my loan blocked?',
                            now() + interval '30 days');
                    INSERT INTO dai_conversation_message (conversation_id, seq, role, content, redacted)
                    VALUES ('99999999-0000-0000-0000-000000000003', 0, 'USER', 'Why was my loan blocked?', false),
                           ('99999999-0000-0000-0000-000000000003', 1, 'ASSISTANT', 'Your score was below 650.', false);
                    """.formatted(Api.ACME));
        }
        seeded = true;
    }

    private static Map<String, Object> facts(Object age, Object kyc, Object score, Object amount) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("customer.age", age);
        f.put("customer.kycStatus", kyc);
        f.put("customer.creditScore", score);
        f.put("loan.amount", amount);
        f.put("customer.email", "someone@example.com");
        return f;
    }

    private static Map<String, Object> eval(String group, Map<String, Object> facts, String... languages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("moduleCode", "LOAN");
        m.put("groupCode", group);
        m.put("facts", facts);
        m.put("languages", List.of(languages));
        return m;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── identity and scope ─────────────────────────────────────────────────────────────────────────────

    @Test
    void migrationsAreAppliedThroughV12() {
        assertThat(jdbc.sql("SELECT max(version::int) FROM dynamic_ai.dai_schema_history WHERE success")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(12);
        assertThat(jdbc.sql("SELECT count(*) FROM dynamic_ai.dai_re_audit_log").query(Long.class).single())
                .isNotNull();
    }

    @Test
    void everyApiNeedsAValidToken() {
        assertThat(api.call("GET", "/api/v1/setup", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/setup", "garbage", null).status()).isEqualTo(401);
        String foreign = Persona.ADMIN.token("another-secret-0123456789abcdef-0123456789", 3600);
        assertThat(api.call("GET", "/api/v1/setup", foreign, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/setup", Persona.ADMIN.token(Api.SECRET, -3600), null).status())
                .isEqualTo(401);
        // a token that is genuine but carries no tenant scope is not a token of this system
        String noTenant = Api.sign(Api.SECRET, new JWTClaimsSet.Builder().issuer(TokenClaims.ISSUER)
                .subject(Persona.ADMIN.userId().toString()).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim(TokenClaims.ROLE, "ADMIN").build());
        assertThat(api.call("GET", "/api/v1/setup", noTenant, null).status()).isEqualTo(401);
        Resp denied = api.call("GET", "/api/v1/setup", null, null);
        assertThat(denied.at("/code").asString()).isEqualTo("unauthenticated");
        assertThat(api.call("GET", "/actuator/health", null, null).status()).isEqualTo(200);
    }

    @Test
    void theScopeComesFromTheTokenAndNeverFromTheRequest() {
        Resp me = api.call("GET", "/api/v1/me?tenantId=" + Api.GLOBEX + "&organizationId=" + Api.CORPORATE,
                Persona.USER.token(), null);

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.at("/tenantId").asString()).isEqualTo(Api.ACME.toString());
        assertThat(me.at("/organizationId").asString()).isEqualTo(Api.RETAIL.toString());
        assertThat(me.at("/role").asString()).isEqualTo("USER");
        // a header cannot widen the scope either
        Resp header = api.call("GET", "/api/v1/setup", Persona.GLOBEX_ADMIN.token(), null);
        assertThat(header.at("/rules").asInt()).isZero();
    }

    @Test
    void aUserSeesTheSetupOfTheirTenant() {
        Resp setup = api.get("/api/v1/setup", Persona.USER);

        assertThat(setup.status()).isEqualTo(200);
        assertThat(setup.at("/tenant").asString()).isEqualTo("Acme Bank");
        assertThat(setup.at("/organization").asString()).isEqualTo("Retail");
        assertThat(setup.at("/rules").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(setup.at("/groups").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(setup.at("/triggers").asInt()).isEqualTo(3);
        assertThat(setup.at("/channels").asInt()).isEqualTo(3);
        assertThat(setup.at("/languages").toString()).contains("en", "hi", "th");

        Resp library = api.get("/api/v1/library", Persona.USER);
        assertThat(values(library.body(), "celName")).contains("customer.age", "loan.amount");
        assertThat(library.toString()).isNotBlank();
        Resp rules = api.get("/api/v1/rules?module=LOAN", Persona.USER);
        JsonNode adult = find(rules.body(), "code", "ADULT");
        assertThat(adult.at("/expression").asString()).isEqualTo("customer.age >= 18");
        assertThat(adult.at("/parameters").toString()).contains("customer.age");
        assertThat(adult.at("/falseMessage/hi").asString()).contains("18");
        assertThat(adult.at("/scope").asString()).isEqualTo("TENANT");
        Resp groups = api.get("/api/v1/rule-groups", Persona.USER);
        JsonNode composite = find(groups.body(), "code", "LOAN_ELIGIBILITY");
        assertThat(composite.at("/policy").asString()).isEqualTo("COMPOSITE");
        assertThat(composite.at("/rules")).hasSize(5);
        assertThat(composite.at("/triggers/0/application").asString()).isEqualTo("loan-portal");
        assertThat(composite.at("/channelCount").asInt()).isEqualTo(3);
        assertThat(api.get("/api/v1/channels", Persona.USER).body()).hasSize(3);
        assertThat(api.get("/api/v1/email-templates", Persona.USER).at("/0/templateRef").asString())
                .isEqualTo("TPL-1001");
        assertThat(api.get("/api/v1/api-endpoints", Persona.USER).toString()).doesNotContain("secret");
        assertThat(values(api.get("/api/v1/modules", Persona.USER).body(), "code")).contains("LOAN");
    }

    /** Every text value of {@code field} anywhere in the tree (arrays inside objects included). */
    private static List<String> values(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getKey().equals(field) && e.getValue().isString()) {
                    out.add(e.getValue().asString());
                }
                out.addAll(values(e.getValue(), field));
            });
        } else if (node.isArray()) {
            node.forEach(n -> out.addAll(values(n, field)));
        }
        return out;
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }

    @Test
    void anotherTenantSeesNothingOfAcmeAndCannotReachItsObjects() {
        Resp setup = api.get("/api/v1/setup", Persona.GLOBEX_ADMIN);
        assertThat(setup.at("/rules").asInt()).isZero();
        assertThat(setup.at("/groups").asInt()).isZero();
        assertThat(api.get("/api/v1/rule-groups", Persona.GLOBEX_ADMIN).body()).isEmpty();
        String acmeGroup = find(api.get("/api/v1/rule-groups", Persona.USER).body(), "code", "LOAN_ELIGIBILITY")
                .at("/id").asString();

        assertThat(api.get("/api/v1/rule-groups/" + acmeGroup, Persona.GLOBEX_ADMIN).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/evaluations", Persona.GLOBEX_ADMIN,
                eval("LOAN_ELIGIBILITY", facts(30, "VERIFIED", 700, 1000.0))).status()).isEqualTo(404);
        assertThat(api.call("PATCH", "/api/v1/rule-groups/" + acmeGroup + "/status", Persona.GLOBEX_ADMIN.token(),
                Map.of("status", "RETIRED")).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/admin/logs/evaluations", Persona.GLOBEX_ADMIN).at("/total").asInt()).isZero();
    }

    // ── authoring ──────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> newRule(String code, String expression) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("moduleCode", "LOAN");
        r.put("code", code);
        r.put("name", "Rule " + code);
        r.put("expression", expression);
        r.put("trueMessage", Map.of("en", "ok " + code));
        r.put("falseMessage", Map.of("en", "failed " + code, "hi", "विफल " + code));
        r.put("trueAction", "ALLOW");
        r.put("falseAction", "BLOCK");
        return r;
    }

    @Test
    void aUserCreatesARuleThatIsCheckedAgainstTheParameterLibrary() {
        String code = unique("age-min");

        Resp created = api.post("/api/v1/rules", Persona.USER, newRule(code, "customer.age >= 21"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("/value/status").asString()).isEqualTo("ACTIVE");
        assertThat(created.at("/value/scope").asString()).isEqualTo("ORGANIZATION");
        assertThat(created.at("/value/parameters").toString()).contains("customer.age");
        assertThat(created.at("/value/falseMessage/hi").asString()).contains(code);
        assertThat(created.at("/warnings")).isEmpty();
        // recorded for impact analysis: customer.age is now used by one more rule
        JsonNode age = null;
        for (JsonNode o : api.get("/api/v1/library", Persona.USER).body()) {
            for (JsonNode a : o.at("/attributes")) {
                if ("customer.age".equals(a.at("/celName").asString())) {
                    age = a;
                }
            }
        }
        assertThat(age).isNotNull();
        assertThat(age.at("/usedByRules").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void invalidExpressionsAreRefusedAtSaveTimeWithoutLeakingValues() {
        Resp unknown = api.post("/api/v1/rules", Persona.USER, newRule(unique("bad"), "customer.agee >= 18"));
        Resp notBoolean = api.post("/api/v1/rules", Persona.USER, newRule(unique("bad"), "customer.age + 1"));
        Resp syntax = api.post("/api/v1/rules", Persona.USER, newRule(unique("bad"), "customer.age >="));
        Resp typeError = api.post("/api/v1/rules", Persona.USER, newRule(unique("bad"), "customer.age >= \"x\""));

        for (Resp r : List.of(unknown, notBoolean, syntax, typeError)) {
            assertThat(r.status()).isEqualTo(422);
            assertThat(r.at("/code").asString()).isEqualTo("invalid_expression");
            assertThat(r.at("/detail").asString()).isNotBlank();
        }
        assertThat(unknown.at("/detail").asString()).contains("customer.agee");
    }

    @Test
    void ruleInputsAreValidated() {
        Map<String, Object> badCode = newRule("has space", "customer.age >= 18");
        Map<String, Object> badModule = newRule(unique("m"), "customer.age >= 18");
        badModule.put("moduleCode", "NOPE");
        Map<String, Object> badAction = newRule(unique("a"), "customer.age >= 18");
        badAction.put("falseAction", "EXPLODE");
        Map<String, Object> badLang = newRule(unique("l"), "customer.age >= 18");
        badLang.put("trueMessage", Map.of("English!", "x"));
        Map<String, Object> retired = newRule(unique("s"), "customer.age >= 18");
        retired.put("status", "RETIRED");

        assertThat(api.post("/api/v1/rules", Persona.USER, badCode).at("/code").asString()).isEqualTo("invalid_code");
        assertThat(api.post("/api/v1/rules", Persona.USER, badModule).at("/code").asString()).isEqualTo("unknown_module");
        assertThat(api.post("/api/v1/rules", Persona.USER, badAction).at("/code").asString()).isEqualTo("invalid_falseAction");
        assertThat(api.post("/api/v1/rules", Persona.USER, badLang).at("/code").asString()).isEqualTo("invalid_language");
        assertThat(api.post("/api/v1/rules", Persona.USER, retired).at("/code").asString()).isEqualTo("invalid_status");
        assertThat(api.post("/api/v1/rules", Persona.USER, "{not json").status()).isEqualTo(400);
        assertThat(api.post("/api/v1/rules", Persona.USER, "{\"moduleCode\":\"LOAN\"}").status()).isEqualTo(400);
    }

    @Test
    void aDuplicateCodeInTheSameScopeIsAConflict() {
        String code = unique("dup");
        assertThat(api.post("/api/v1/rules", Persona.USER, newRule(code, "customer.age >= 18")).status()).isEqualTo(201);

        Resp again = api.post("/api/v1/rules", Persona.USER, newRule(code, "customer.age >= 19"));

        assertThat(again.status()).isEqualTo(409);
        // the same code in another organization is a different rule
        assertThat(api.post("/api/v1/rules", Persona.CORP, newRule(code, "customer.age >= 19")).status()).isEqualTo(201);
    }

    @Test
    void onlyAnAdministratorSharesAnItemWithTheWholeTenant() {
        Map<String, Object> shared = newRule(unique("shared"), "customer.age >= 18");
        shared.put("scope", "TENANT");

        Resp byUser = api.post("/api/v1/rules", Persona.USER, shared);
        Resp byAdmin = api.post("/api/v1/rules", Persona.ADMIN, shared);

        assertThat(byUser.status()).isEqualTo(403);
        assertThat(byUser.at("/code").asString()).isEqualTo("scope_forbidden");
        assertThat(byAdmin.status()).isEqualTo(201);
        assertThat(byAdmin.at("/value/scope").asString()).isEqualTo("TENANT");
    }

    @Test
    void anOrganizationsPrivateRulesAreInvisibleToOtherOrganizations() {
        String code = unique("private");
        String id = api.post("/api/v1/rules", Persona.USER, newRule(code, "customer.age >= 18")).at("/value/id").asString();

        assertThat(api.get("/api/v1/rules/" + id, Persona.USER).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/rules/" + id, Persona.CORP).status()).isEqualTo(404);
        assertThat(values(api.get("/api/v1/rules?module=LOAN", Persona.CORP).body(), "code"))
                .doesNotContain(code).contains("ADULT");
        assertThat(values(api.get("/api/v1/rules?module=LOAN", Persona.USER).body(), "code")).contains(code);
    }

    private Map<String, Object> newGroup(String code, String policy, List<String> rules) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("moduleCode", "LOAN");
        g.put("code", code);
        g.put("name", "Group " + code);
        g.put("policy", policy);
        g.put("rules", rules.stream().map(r -> Map.of("ruleCode", r)).toList());
        return g;
    }

    @Test
    void aUserCreatesAGroupAndEvaluatesItInTheirLanguage() {
        String code = unique("grp");
        Map<String, Object> g = newGroup(code, "COMPOSITE", List.of("ADULT", "KYC_VERIFIED", "CREDIT_SCORE_MIN"));
        g.put("compositeTrueMessage", Map.of("en", "All good", "hi", "सब ठीक है"));
        g.put("compositeFalseMessage", Map.of("en", "Something failed", "hi", "कुछ विफल हुआ"));
        g.put("triggers", List.of(Map.of("application", "loan-portal", "type", "FORM_ACTION", "formCode", "LOAN_APPLICATION",
                "actionCode", unique("SUBMIT-" + code.substring(4, 8)).replace("-", "_"))));

        Resp created = api.post("/api/v1/rule-groups", Persona.USER, g);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("/value/scope").asString()).isEqualTo("ORGANIZATION");
        assertThat(created.at("/value/rules")).hasSize(3);
        assertThat(created.at("/value/rules/0/sequence").asInt()).isEqualTo(10);
        assertThat(created.at("/value/rules/2/sequence").asInt()).isEqualTo(30);
        assertThat(created.at("/value/triggers")).hasSize(1);
        assertThat(created.at("/warnings")).isEmpty();

        Resp pass = api.post("/api/v1/evaluations", Persona.USER, eval(code, facts(30, "VERIFIED", 700, 1000.0), "hi", "en"));
        assertThat(pass.status()).isEqualTo(200);
        assertThat(pass.at("/decision").asString()).isEqualTo("ALLOW");
        assertThat(pass.at("/primaryMessage/text").asString()).isEqualTo("सब ठीक है");
        assertThat(pass.at("/primaryMessage/language").asString()).isEqualTo("hi");
        assertThat(pass.at("/results")).hasSize(3);

        Resp fail = api.post("/api/v1/evaluations", Persona.USER, eval(code, facts(16, "VERIFIED", 700, 1000.0), "en"));
        assertThat(fail.at("/decision").asString()).isEqualTo("BLOCK");
        assertThat(fail.at("/primaryMessage/text").asString()).isEqualTo("Something failed");
        assertThat(fail.at("/messages").toString()).contains("Customer must be at least 18 years old.");
    }

    @Test
    void aTriggerEvaluatesEveryGroupBoundToTheFormAction() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "loan-portal");
        request.put("type", "FORM_ACTION");
        request.put("formCode", "LOAN_APPLICATION");
        request.put("actionCode", "SUBMIT");
        request.put("facts", facts(16, "PENDING", 500, 900000.0));
        request.put("languages", List.of("th", "en"));

        Resp r = api.post("/api/v1/triggers/evaluate", Persona.USER, request);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/decision").asString()).isEqualTo("BLOCK");
        assertThat(r.at("/groups")).hasSize(1);
        assertThat(r.at("/groups/0/groupCode").asString()).isEqualTo("LOAN_ELIGIBILITY");
        assertThat(r.at("/groups/0/primaryMessage/language").asString()).isEqualTo("th");
        // the planned communications are described, never sent, and the recipient is not returned
        assertThat(r.at("/groups/0/channels").toString()).contains("EMAIL", "PUSH", "API");
        assertThat(r.raw()).doesNotContain("someone@example.com");
        request.put("actionCode", "NO_SUCH_ACTION");
        assertThat(api.post("/api/v1/triggers/evaluate", Persona.USER, request).at("/decision").asString())
                .isEqualTo("ALLOW"); // nothing bound = allow (LLD-18 §7)
    }

    @Test
    void groupInputsAreValidated() {
        Map<String, Object> unknownRule = newGroup(unique("g"), "ALL_MATCH", List.of("DOES_NOT_EXIST"));
        Map<String, Object> badPolicy = newGroup(unique("g"), "SOMETIMES", List.of("ADULT"));
        Map<String, Object> composite = newGroup(unique("g"), "FIRST_MATCH", List.of("ADULT"));
        composite.put("compositeFalseMessage", Map.of("en", "x"));
        Map<String, Object> twice = newGroup(unique("g"), "ALL_MATCH", List.of("ADULT", "ADULT"));
        Map<String, Object> sameSequence = newGroup(unique("g"), "ALL_MATCH", List.of("ADULT", "KYC_VERIFIED"));
        sameSequence.put("rules", List.of(Map.of("ruleCode", "ADULT", "sequence", 5),
                Map.of("ruleCode", "KYC_VERIFIED", "sequence", 5)));
        Map<String, Object> badTrigger = newGroup(unique("g"), "ALL_MATCH", List.of("ADULT"));
        badTrigger.put("triggers", List.of(Map.of("application", "a", "type", "FORM_FIELD", "formCode", "f",
                "actionCode", "ON_CHANGE")));

        assertThat(api.post("/api/v1/rule-groups", Persona.USER, unknownRule).at("/code").asString()).isEqualTo("unknown_rule");
        assertThat(api.post("/api/v1/rule-groups", Persona.USER, badPolicy).at("/code").asString()).isEqualTo("invalid_policy");
        assertThat(api.post("/api/v1/rule-groups", Persona.USER, composite).at("/code").asString())
                .isEqualTo("composite_message_not_allowed");
        assertThat(api.post("/api/v1/rule-groups", Persona.USER, twice).at("/code").asString()).isEqualTo("duplicate_rule");
        assertThat(api.post("/api/v1/rule-groups", Persona.USER, sameSequence).at("/code").asString())
                .isEqualTo("duplicate_sequence");
        assertThat(api.post("/api/v1/rule-groups", Persona.USER, badTrigger).at("/code").asString()).isEqualTo("invalid_trigger");
    }

    @Test
    void aTenantWideGroupCannotContainAnOrganizationsPrivateRule() {
        String privateRule = unique("priv");
        api.post("/api/v1/rules", Persona.ADMIN, newRule(privateRule, "customer.age >= 18")); // Retail-only
        Map<String, Object> shared = newGroup(unique("g"), "ALL_MATCH", List.of(privateRule));
        shared.put("scope", "TENANT");
        Map<String, Object> mine = newGroup(unique("g"), "ALL_MATCH", List.of(privateRule));

        Resp refused = api.post("/api/v1/rule-groups", Persona.ADMIN, shared);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.at("/code").asString()).isEqualTo("unknown_rule");
        assertThat(refused.at("/detail").asString()).contains("not shared with the whole tenant");
        assertThat(api.post("/api/v1/rule-groups", Persona.ADMIN, mine).status()).isEqualTo(201);
    }

    @Test
    void aUserChangesOnlyTheirOwnOrganizationsItemsAnAdministratorAny() {
        String tenantWide = find(api.get("/api/v1/rule-groups", Persona.USER).body(), "code", "LOAN_FULL_REPORT")
                .at("/id").asString();
        Map<String, Object> status = Map.of("status", "ACTIVE");

        Resp byUser = api.call("PATCH", "/api/v1/rule-groups/" + tenantWide + "/status", Persona.USER.token(), status);
        Resp byCorp = api.call("PATCH", "/api/v1/rule-groups/" + tenantWide + "/status", Persona.CORP.token(), status);
        Resp byAdmin = api.call("PATCH", "/api/v1/rule-groups/" + tenantWide + "/status", Persona.ADMIN.token(), status);

        assertThat(byUser.status()).isEqualTo(403);
        assertThat(byUser.at("/code").asString()).isEqualTo("not_yours");
        assertThat(byCorp.status()).isEqualTo(403);
        assertThat(byAdmin.status()).isEqualTo(200);
    }

    @Test
    void replacingAGroupIsOptimisticAndKeepsItsIdentity() {
        String code = unique("rep");
        Resp created = api.post("/api/v1/rule-groups", Persona.USER, newGroup(code, "ALL_MATCH", List.of("ADULT")));
        String id = created.at("/value/id").asString();
        long version = created.at("/value/rowVersion").asLong();
        Map<String, Object> change = newGroup(code, "EVALUATE_ALL", List.of("ADULT", "KYC_VERIFIED"));
        change.put("expectedRowVersion", version);

        Resp updated = api.call("PUT", "/api/v1/rule-groups/" + id, Persona.USER.token(), change);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.at("/value/policy").asString()).isEqualTo("EVALUATE_ALL");
        assertThat(updated.at("/value/rules")).hasSize(2);
        assertThat(updated.at("/value/rowVersion").asLong()).isEqualTo(version + 1);

        Resp stale = api.call("PUT", "/api/v1/rule-groups/" + id, Persona.USER.token(), change); // old version
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.at("/code").asString()).isEqualTo("stale_version");

        Map<String, Object> rename = newGroup(unique("other"), "EVALUATE_ALL", List.of("ADULT"));
        rename.put("expectedRowVersion", version + 1);
        assertThat(api.call("PUT", "/api/v1/rule-groups/" + id, Persona.USER.token(), rename).at("/code").asString())
                .isEqualTo("immutable_identity");
        Map<String, Object> noVersion = newGroup(code, "EVALUATE_ALL", List.of("ADULT"));
        assertThat(api.call("PUT", "/api/v1/rule-groups/" + id, Persona.USER.token(), noVersion).at("/code").asString())
                .isEqualTo("expected_row_version_required");
    }

    @Test
    void aRetiredGroupStopsBeingEvaluatedAtOnce() {
        String code = unique("ret");
        String id = api.post("/api/v1/rule-groups", Persona.USER, newGroup(code, "ALL_MATCH", List.of("ADULT")))
                .at("/value/id").asString();
        Map<String, Object> request = eval(code, facts(30, "VERIFIED", 700, 1000.0));
        assertThat(api.post("/api/v1/evaluations", Persona.USER, request).status()).isEqualTo(200);

        Resp retired = api.call("PATCH", "/api/v1/rule-groups/" + id + "/status", Persona.USER.token(),
                Map.of("status", "RETIRED"));

        assertThat(retired.at("/status").asString()).isEqualTo("RETIRED");
        assertThat(api.post("/api/v1/evaluations", Persona.USER, request).status()).isEqualTo(404);
        assertThat(api.call("PATCH", "/api/v1/rule-groups/" + id + "/status", Persona.USER.token(),
                Map.of("status", "WHATEVER")).at("/code").asString()).isEqualTo("invalid_status");
    }

    @Test
    void aDraftGroupIsSavedWithAWarningAndDoesNotRun() {
        Map<String, Object> g = newGroup(unique("draft"), "ALL_MATCH", List.of("ADULT"));
        g.put("status", "DRAFT");

        Resp created = api.post("/api/v1/rule-groups", Persona.USER, g);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("/warnings/0").asString()).contains("DRAFT");
        assertThat(api.post("/api/v1/evaluations", Persona.USER, eval(g.get("code").toString(),
                facts(30, "VERIFIED", 700, 1000.0))).status()).isEqualTo(404);
    }

    // ── evaluation ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aRuleThatCannotBeEvaluatedFailsClosedWithoutEchoingTheValues() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("customer.age", 30); // everything else is missing

        Resp r = api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", missing));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/decision").asString()).isEqualTo("BLOCK");
        assertThat(r.at("/results").toString()).contains("MISSING_PARAMETER");
        Map<String, Object> wrongType = facts("thirty", "VERIFIED", 700, 1000.0);
        Resp bad = api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", wrongType));
        assertThat(bad.at("/results").toString()).contains("INVALID_PARAMETER");
        assertThat(bad.raw()).doesNotContain("thirty");
    }

    @Test
    void evaluationRequestsAreBounded() {
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 201; i++) {
            many.put("customer.f" + i, i);
        }
        Resp tooMany = api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", many));
        Map<String, Object> badName = new LinkedHashMap<>();
        badName.put("customer.age; DROP TABLE x", 1);
        Resp badKey = api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", badName));
        Resp badLang = api.post("/api/v1/evaluations", Persona.USER,
                eval("LOAN_FULL_REPORT", Map.of("customer.age", 1), "not a language"));
        String huge = "{\"moduleCode\":\"LOAN\",\"groupCode\":\"X\",\"facts\":{\"customer.email\":\""
                + "x".repeat(300_000) + "\"}}";

        assertThat(tooMany.status()).isEqualTo(422);
        assertThat(tooMany.at("/code").asString()).isEqualTo("too_many_facts");
        assertThat(badKey.at("/code").asString()).isEqualTo("invalid_fact_name");
        assertThat(badLang.at("/code").asString()).isEqualTo("invalid_language");
        assertThat(api.call("POST", "/api/v1/evaluations", Persona.USER.token(), huge).status()).isEqualTo(413);
    }

    @Test
    void factValuesAreNeverStoredLoggedOrReturnedByTheLogs() {
        Map<String, Object> facts = facts(30, "VERIFIED", 700, 1000.0);
        facts.put("customer.email", SENTINEL + "@example.com");
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_ELIGIBILITY", facts));

        String everywhere = jdbc.sql("""
                SELECT coalesce(string_agg(a::text, ' '), '') FROM (
                    SELECT row_to_json(e) AS a FROM dynamic_ai.dai_re_evaluation e
                    UNION ALL SELECT row_to_json(r) FROM dynamic_ai.dai_re_evaluation_result r
                    UNION ALL SELECT row_to_json(l) FROM dynamic_ai.dai_re_audit_log l) x""")
                .query(String.class).single();

        assertThat(everywhere).doesNotContain(SENTINEL);
        assertThat(api.get("/api/v1/admin/logs/audit?size=200", Persona.ADMIN).raw()).doesNotContain(SENTINEL);
        assertThat(api.get("/api/v1/admin/logs/evaluations?size=200", Persona.ADMIN).raw()).doesNotContain(SENTINEL);
    }

    // ── admin logs ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void logsAreForAdministratorsOnly() {
        for (String path : List.of("/summary", "/evaluations", "/audit", "/conversations")) {
            assertThat(api.get("/api/v1/admin/logs" + path, Persona.USER).status()).as(path).isEqualTo(403);
            assertThat(api.get("/api/v1/admin/logs" + path, Persona.CORP).status()).as(path).isEqualTo(403);
            assertThat(api.get("/api/v1/admin/logs" + path, Persona.ADMIN).status()).as(path).isEqualTo(200);
        }
        assertThat(api.get("/api/v1/admin/logs/summary", Persona.USER).at("/code").asString()).isEqualTo("forbidden");
        assertThat(api.call("GET", "/api/v1/admin/logs/summary", null, null).status()).isEqualTo(401);
    }

    @Test
    void evaluationLogsShowDecisionsCountsAndPerRuleDetail() {
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", facts(16, "VERIFIED", 700, 1000.0)));

        Resp list = api.get("/api/v1/admin/logs/evaluations?group=LOAN_FULL_REPORT&decision=block", Persona.ADMIN);

        assertThat(list.status()).isEqualTo(200);
        JsonNode first = list.at("/items/0");
        assertThat(first.at("/groupCode").asString()).isEqualTo("LOAN_FULL_REPORT");
        assertThat(first.at("/moduleCode").asString()).isEqualTo("LOAN");
        assertThat(first.at("/decision").asString()).isEqualTo("BLOCK");
        assertThat(first.at("/rulesFalse").asInt()).isEqualTo(1);
        assertThat(first.at("/rulesTrue").asInt()).isEqualTo(4);
        assertThat(first.at("/policy").asString()).isEqualTo("EVALUATE_ALL");
        assertThat(list.at("/total").asLong()).isPositive();

        Resp detail = api.get("/api/v1/admin/logs/evaluations/" + first.at("/id").asString(), Persona.ADMIN);
        assertThat(detail.at("/results")).hasSize(5);
        assertThat(detail.at("/results/0/ruleCode").asString()).isEqualTo("ADULT");
        assertThat(detail.at("/results/0/outcome").asString()).isEqualTo("FALSE");
        assertThat(api.get("/api/v1/admin/logs/evaluations/" + UUID.randomUUID(), Persona.ADMIN).status()).isEqualTo(404);
    }

    @Test
    void evaluationLogsFilterPageAndRejectInjection() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("customer.age", 30);
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", bad)); // errors: missing parameters

        Resp errors = api.get("/api/v1/admin/logs/evaluations?errorsOnly=true", Persona.ADMIN);
        assertThat(errors.at("/total").asLong()).isPositive();
        for (JsonNode e : errors.at("/items")) {
            assertThat(e.at("/rulesError").asInt()).isPositive();
        }
        Resp paged = api.get("/api/v1/admin/logs/evaluations?size=1&page=0", Persona.ADMIN);
        assertThat(paged.at("/items")).hasSize(1);
        assertThat(paged.at("/size").asInt()).isEqualTo(1);
        assertThat(api.get("/api/v1/admin/logs/evaluations?size=100000", Persona.ADMIN).at("/size").asInt()).isEqualTo(200);
        assertThat(api.get("/api/v1/admin/logs/evaluations?from=" + Instant.now().plusSeconds(3600), Persona.ADMIN)
                .at("/total").asLong()).isZero();
        Resp injected = api.get("/api/v1/admin/logs/evaluations?group=" + java.net.URLEncoder.encode(
                "x' OR '1'='1", java.nio.charset.StandardCharsets.UTF_8), Persona.ADMIN);
        assertThat(injected.status()).isEqualTo(200);
        assertThat(injected.at("/total").asLong()).isZero();
        assertThat(api.get("/api/v1/admin/logs/evaluations?from=notadate", Persona.ADMIN).status()).isEqualTo(400);
    }

    @Test
    void theAuditTrailRecordsWhoChangedWhatAndWhoEvaluated() {
        String code = unique("audit");
        api.post("/api/v1/rules", Persona.USER, newRule(code, "customer.age >= 18"));
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_FULL_REPORT", facts(30, "VERIFIED", 700, 1000.0)));

        Resp created = api.get("/api/v1/admin/logs/audit?action=rule_created&actor=user", Persona.ADMIN);
        JsonNode entry = null;
        for (JsonNode e : created.at("/items")) {
            if (code.equals(e.at("/entityCode").asString())) {
                entry = e;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.at("/actorRole").asString()).isEqualTo("USER");
        assertThat(entry.at("/actorName").asString()).contains("user");
        assertThat(entry.at("/entityType").asString()).isEqualTo("RULE");
        assertThat(entry.at("/details").asString()).contains("customer.age").doesNotContain("18");

        Resp evaluated = api.get("/api/v1/admin/logs/audit?action=RULE_GROUP_EVALUATED&entityType=EVALUATION", Persona.ADMIN);
        assertThat(evaluated.at("/total").asLong()).isPositive();
        assertThat(evaluated.at("/items/0/summary").asString()).startsWith("Evaluated LOAN/");
        assertThat(api.get("/api/v1/admin/logs/audit?actor=nobody-like-this", Persona.ADMIN).at("/total").asLong()).isZero();
    }

    @Test
    void organizationAdministratorsSeeOnlyTheirOrganizationsAndTenantWideEntries() {
        String code = unique("scoped");
        api.post("/api/v1/rules", Persona.CORP, newRule(code, "customer.age >= 18"));

        String retailView = api.get("/api/v1/admin/logs/audit?size=200", Persona.ADMIN).raw();

        assertThat(retailView).doesNotContain(code); // a Corporate change is not in the Retail administrator's view
        Persona corpAdmin = new Persona("corp.admin", UUID.fromString("33333333-0000-0000-0000-0000000000a1"), true,
                Api.ACME, "Acme Bank", Api.CORPORATE, "Corporate");
        assertThat(api.get("/api/v1/admin/logs/audit?size=200", corpAdmin).raw()).contains(code);
        Persona tenantAdmin = new Persona("acme.admin", UUID.fromString("33333333-0000-0000-0000-0000000000a2"), true,
                Api.ACME, "Acme Bank", null, null);
        assertThat(api.get("/api/v1/admin/logs/audit?size=200", tenantAdmin).raw()).contains(code);
    }

    @Test
    void theSummaryGivesTheDashboardNumbers() {
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_ELIGIBILITY", facts(30, "VERIFIED", 700, 1000.0)));
        api.post("/api/v1/evaluations", Persona.USER, eval("LOAN_ELIGIBILITY", facts(10, "VERIFIED", 700, 1000.0)));

        Resp s = api.get("/api/v1/admin/logs/summary?hours=24", Persona.ADMIN);

        assertThat(s.status()).isEqualTo(200);
        assertThat(s.at("/evaluations").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(s.at("/allow").asLong() + s.at("/warn").asLong() + s.at("/block").asLong())
                .isEqualTo(s.at("/evaluations").asLong());
        assertThat(s.at("/p95Millis").asDouble()).isGreaterThanOrEqualTo(s.at("/p50Millis").asDouble());
        assertThat(s.at("/series")).isNotEmpty();
        assertThat(s.at("/topGroups/0/groupCode").asString()).isNotBlank();
        assertThat(s.at("/auditByAction").toString()).contains("RULE_GROUP_EVALUATED");
        assertThat(api.get("/api/v1/admin/logs/summary?hours=0", Persona.ADMIN).at("/hours").asInt()).isEqualTo(1);
        assertThat(api.get("/api/v1/admin/logs/summary?hours=99999", Persona.ADMIN).at("/hours").asInt()).isEqualTo(720);
    }

    @Test
    void theChatLogShowsTheTenantsConversationsAndTranscripts() {
        Resp list = api.get("/api/v1/admin/logs/conversations", Persona.ADMIN);

        assertThat(list.at("/total").asLong()).isEqualTo(1);
        assertThat(list.at("/items/0/title").asString()).isEqualTo("Why was my loan blocked?");
        assertThat(list.at("/items/0/messages").asInt()).isEqualTo(2);
        String id = list.at("/items/0/id").asString();
        Resp detail = api.get("/api/v1/admin/logs/conversations/" + id, Persona.ADMIN);
        assertThat(detail.at("/messages/1/role").asString()).isEqualTo("ASSISTANT");
        assertThat(detail.at("/messages/1/content").asString()).isEqualTo("Your score was below 650.");

        // another tenant's administrator sees none of it and cannot fetch it by id
        assertThat(api.get("/api/v1/admin/logs/conversations", Persona.GLOBEX_ADMIN).at("/total").asLong()).isZero();
        assertThat(api.get("/api/v1/admin/logs/conversations/" + id, Persona.GLOBEX_ADMIN).status()).isEqualTo(404);
    }

    @Test
    void errorsAreProblemDocumentsWithoutInternals() {
        Resp r = api.get("/api/v1/rule-groups/not-a-uuid", Persona.USER);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.at("/code").asString()).isEqualTo("invalid_request");
        assertThat(r.raw()).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(api.get("/api/v1/nothing-here", Persona.USER).status()).isIn(403, 404);
        assertThat(api.call("DELETE", "/api/v1/rules/" + UUID.randomUUID(), Persona.ADMIN.token(), null).status())
                .isIn(403, 404, 405);
        assertThat(new ArrayList<>(List.of(api.get("/api/v1/rules/" + UUID.randomUUID(), Persona.USER).status())))
                .containsExactly(404);
    }
}
