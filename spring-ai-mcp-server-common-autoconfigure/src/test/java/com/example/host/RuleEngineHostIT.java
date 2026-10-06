package com.example.host;

import com.springaimcpservercommon.ruleengine.EvaluationRequest;
import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.model.Action;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule engine inside a real host (LLD-18): auto-configuration only, the host's JWT security, real PostgreSQL.
 * Authoring goes over HTTP as separate people; evaluation uses the {@link RuleEngine} bean the way an integrating
 * application would. Without Docker set {@code DAI_IT_JDBC_URL} (and {@code DAI_IT_USER}/{@code DAI_IT_PASSWORD}).
 */
class RuleEngineHostIT {

    private static final String EXTERNAL_DB = System.getenv("DAI_IT_JDBC_URL");
    private static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? new PostgreSQLContainer("postgres:17-alpine") : null;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final com.nimbusds.jose.jwk.RSAKey KEY;
    private static final String API = "/dynamic-ai/admin/api/v1";

    static {
        try {
            KEY = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test").generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConfigurableApplicationContext context;
    private static MockMvc mvc;

    /** The host. */
    @SpringBootApplication
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class HostApp {

        @Bean
        ChatModel openAiChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(call(prompt));
                }

                @Override
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().model("scripted").build();
                }
            };
        }

        @Bean
        org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() throws com.nimbusds.jose.JOSEException {
            return org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        }

        @Bean
        SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults())).build();
        }
    }

    private static String bearer(String user) {
        try {
            String scope = user.startsWith("admin") ? "openid dai.admin" : "openid";
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(user).issuer("https://idp.test")
                    .claim("scope", scope).expirationTime(new java.util.Date(System.currentTimeMillis() + 600_000)).build();
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                    com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void start() {
        Map<String, Object> props = new HashMap<>();
        if (POSTGRES != null) {
            POSTGRES.start();
            props.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            props.put("spring.datasource.username", POSTGRES.getUsername());
            props.put("spring.datasource.password", POSTGRES.getPassword());
        } else {
            props.put("spring.datasource.url", EXTERNAL_DB);
            props.put("spring.datasource.username", System.getenv().getOrDefault("DAI_IT_USER", "dai"));
            props.put("spring.datasource.password", System.getenv().getOrDefault("DAI_IT_PASSWORD", "dai"));
        }
        props.put("dynamic.ai.agent.environment.tier", "DEV");
        props.put("dynamic.ai.agent.environment.application-name", "rules-it");
        props.put("dynamic.ai.agent.store.validate-schema", "true");
        props.put("dynamic.ai.agent.rule-engine.enabled", "true");
        props.put("dynamic.ai.agent.rule-engine.poll-interval", "1s");
        props.put("dynamic.ai.agent.rule-engine.api-hosts.dev[0]", "localhost");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].source", "AUTHORITY");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].match-value", "SCOPE_dai.admin");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].role", "PLATFORM_ADMIN");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].source", "AUTHORITY");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].match-value", "SCOPE_dai.admin");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].role", "SECURITY_ADMIN");
        SpringApplication app = new SpringApplication(HostApp.class);
        app.setDefaultProperties(props);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type -> new org.springframework.web.context.support.GenericWebApplicationContext(
                new org.springframework.mock.web.MockServletContext()));
        var captured = new java.util.concurrent.atomic.AtomicReference<
                org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport>();
        app.addInitializers(ctx -> captured.set(org.springframework.boot.autoconfigure.condition
                .ConditionEvaluationReport.get(ctx.getBeanFactory())));
        context = app.run();
        // what did not match in the rule-engine configuration (printed always: it is what you need when a bean is missing)
        captured.get().getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (source.contains("RuleEngine") && !outcomes.isFullMatch()) {
                outcomes.forEach(o -> System.out.println("DAI-RULES-CONDITION " + source + " -> " + o.getOutcome().getMessage()));
            }
        });
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
    }

    private static JsonNode call(String method, String path, String user, Object body, int expected) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", bearer(user));
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString();
        assertThat(response.getStatus()).as("%s %s as %s -> %s", method, path, user, text).isEqualTo(expected);
        return text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
    }

    private static String principalId(String user) throws Exception {
        return call("GET", API + "/me", user, null, 200).get("principal").get("principalId").asString();
    }

    @Test
    void rulesAreAuthoredReviewedByAnotherPersonPublishedAndThenEvaluatedByTheApplication() throws Exception {
        // ---- the platform library (global administrator)
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String module = "M" + suffix;
        call("POST", API + "/rule-library/modules", "admin", Map.of("code", module, "name", "Loans"), 201);
        String object = "cust" + suffix.toLowerCase();
        String objectId = call("POST", API + "/rule-library/objects", "admin",
                Map.of("code", object, "name", "Customer", "moduleCode", module), 201).get("id").asString();
        call("POST", API + "/rule-library/objects/" + objectId + "/attributes", "admin",
                Map.of("code", "age", "name", "Age", "dataType", "INT", "required", true), 201);
        JsonNode bundle = call("POST", API + "/rule-library/bundles", "admin", Map.of("code", "ok." + suffix,
                "texts", Map.of("en", "Adult", "hi", "वयस्क")), 201);
        JsonNode failBundle = call("POST", API + "/rule-library/bundles", "admin", Map.of("code", "no." + suffix,
                "texts", Map.of("en", "Too young", "hi", "बहुत छोटा")), 201);

        // ---- a workspace is the tenant; two people with rights in it
        String ws = call("POST", API + "/workspaces", "admin", Map.of("slug", "rules-" + suffix.toLowerCase(), "name", "Rules"), 201)
                .get("id").asString();
        String author = principalId("admin");
        String reviewer = principalId("admin2");
        call("POST", API + "/workspaces/" + ws + "/members", "admin", Map.of("principalId", author, "role", "AUTHOR"), 201);
        call("POST", API + "/workspaces/" + ws + "/members", "admin", Map.of("principalId", reviewer, "role", "APPROVER"), 201);
        String base = API + "/workspaces/" + ws + "/rule-engine";

        // ---- default deny for everyone else
        call("GET", base + "/rules", "alice", null, 403);
        call("POST", API + "/rule-library/modules", "alice", Map.of("code", "NOPE", "name", "x"), 403);

        // ---- the expression editor says what is wrong before anything is saved
        assertThat(call("POST", base + "/expressions/validate", "admin",
                Map.of("expression", object + ".height > 3"), 200).get("valid").asBoolean()).isFalse();
        JsonNode ok = call("POST", base + "/expressions/validate", "admin", Map.of("expression", object + ".age >= 18"), 200);
        assertThat(ok.get("valid").asBoolean()).isTrue();
        assertThat(ok.get("parameters").get(0).asString()).isEqualTo(object + ".age");
        assertThat(call("POST", base + "/expressions/test", "admin",
                Map.of("expression", object + ".age >= 18", "facts", Map.of(object, Map.of("age", 20))), 200)
                .get("outcome").asString()).isEqualTo("TRUE");

        // ---- a bad rule is refused with field-level problems
        Map<String, Object> content = Map.of("name", "Adult", "expression", object + ".age >= 18",
                "trueMessageBundleId", bundle.get("id").asString(), "falseMessageBundleId", failBundle.get("id").asString(),
                "trueAction", "ALLOW", "falseAction", "BLOCK");
        JsonNode refused = call("POST", base + "/rules", "admin", Map.of("moduleCode", module, "code", "BAD",
                "content", Map.of("name", "x", "expression", object + ".nope > 1", "trueAction", "ALLOW", "falseAction", "BLOCK")), 400);
        assertThat(refused.toString()).contains("expression");

        // ---- author drafts, submits; the submitter cannot approve; the reviewer can
        JsonNode created = call("POST", base + "/rules", "admin", Map.of("moduleCode", module, "code", "ADULT", "content", content), 201);
        String ruleId = created.get("subjectId").asString();
        call("POST", base + "/rules/" + ruleId + "/submit", "admin", null, 200);
        JsonNode self = call("POST", base + "/rules/" + ruleId + "/approve", "admin", Map.of("comment", "me"), 409);
        assertThat(self.get("title").asString()).isEqualTo("four_eyes");
        call("POST", base + "/rules/" + ruleId + "/approve", "admin2", Map.of("comment", "ok"), 200);
        assertThat(call("POST", base + "/rules/" + ruleId + "/publish", "admin2", null, 200).get("state").asString())
                .isEqualTo("PUBLISHED");

        // ---- a group of that rule, through the same lifecycle
        Map<String, Object> group = Map.of("name", "Eligibility", "policy", "COMPOSITE", "matchOn", "TRUE",
                "compositeTrueBundleId", bundle.get("id").asString(), "compositeFalseBundleId", failBundle.get("id").asString(),
                "compositeTrueAction", "ALLOW", "compositeFalseAction", "BLOCK", "onError", "BLOCK",
                "members", List.of(Map.of("ruleId", ruleId, "sequence", 10, "enabled", true)));
        String groupId = call("POST", base + "/groups", "admin", Map.of("moduleCode", module, "code", "ELIGIBILITY", "content", group), 201)
                .get("subjectId").asString();
        call("POST", base + "/groups/" + groupId + "/submit", "admin", null, 200);
        call("POST", base + "/groups/" + groupId + "/approve", "admin2", null, 200);
        call("POST", base + "/groups/" + groupId + "/publish", "admin2", null, 200);

        // ---- the integrating application evaluates (the engine sees the publication within the poll interval)
        RuleEngine engine = context.getBean(RuleEngine.class);
        UUID tenant = UUID.fromString(ws);
        Action decision = Action.ALLOW;
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                decision = engine.evaluate(new EvaluationRequest(tenant, null, module, "ELIGIBILITY",
                        Map.of(object, Map.of("age", 12)), List.of("hi"))).response().decision();
                break;
            } catch (com.springaimcpservercommon.ruleengine.UnknownRuleGroupException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(200);
            }
        }
        assertThat(decision).isEqualTo(Action.BLOCK);
        var adult = engine.evaluate(new EvaluationRequest(tenant, null, module, "ELIGIBILITY", Map.of(object, Map.of("age", 40)),
                List.of("hi"))).response();
        assertThat(adult.decision()).isEqualTo(Action.ALLOW);
        assertThat(adult.messages().getFirst().text()).isEqualTo("वयस्क");

        // ---- impact analysis and the evaluation log
        assertThat(call("GET", API + "/rule-library", "admin", null, 200).toString()).contains(object + "");
        assertThat(call("GET", base + "/evaluations?groupId=" + groupId, "admin", null, 200).size()).isGreaterThanOrEqualTo(2);

        // ---- another workspace cannot see this one's rules
        String other = call("POST", API + "/workspaces", "admin", Map.of("slug", "other-" + suffix.toLowerCase(), "name", "Other"), 201)
                .get("id").asString();
        call("GET", API + "/workspaces/" + other + "/rule-engine/rules/" + ruleId, "admin", null, 404);
    }

    @Test
    void anExternalApiNeedsAConfirmationThatTheApiReportsAsAConflict() throws Exception {
        String ws = call("POST", API + "/workspaces", "admin",
                Map.of("slug", "api-" + UUID.randomUUID().toString().substring(0, 8), "name", "Api"), 201).get("id").asString();
        String base = API + "/workspaces/" + ws + "/rule-engine";

        JsonNode check = call("POST", base + "/api-endpoints/check", "admin", Map.of("url", "https://partner.example.org/hook"), 200);
        assertThat(check.get("verdict").asString()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(check.get("message").asString()).contains("external API");

        Map<String, Object> body = new HashMap<>(Map.of("name", "partner", "url", "https://partner.example.org/hook"));
        assertThat(call("POST", base + "/api-endpoints", "admin", body, 409).get("title").asString()).isEqualTo("confirmation_required");
        body.put("confirmExternal", true);
        JsonNode saved = call("POST", base + "/api-endpoints", "admin", body, 201);
        assertThat(saved.get("environment").asString()).isEqualTo("EXTERNAL");
        assertThat(saved.get("externalConfirmedBy").asString()).isNotBlank();

        // this node is DEV: a localhost API is its own environment and needs no confirmation
        assertThat(call("POST", base + "/api-endpoints", "admin", Map.of("name", "local", "url", "http://localhost:9000/h"), 201)
                .get("environment").asString()).isEqualTo("DEV");
        call("GET", base + "/deliveries/dead", "admin", null, 200);
    }
}
