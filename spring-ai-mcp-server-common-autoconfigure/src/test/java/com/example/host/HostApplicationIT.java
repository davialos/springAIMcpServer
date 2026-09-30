package com.example.host;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A host application exactly as the integration guide describes one: Spring Boot, the starter on the classpath, the
 * host's own DataSource and security, one ChatModel bean. Nothing of the library is configured by hand; everything
 * comes from the auto-configurations. Catches what bean-level wiring tests cannot: mapping, ordering, security.
 */
class HostApplicationIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Hello from the model"))));
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
            return org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .build();
        }

        /** The host's own chain: everything authenticated, bearer tokens, Spring's default CSRF. */
        @Bean
        SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                    .build();
        }

        /** One host endpoint, to prove the library's chains leave the host's protection alone. */
        @org.springframework.web.bind.annotation.RestController
        static class HostEndpoint {
            @org.springframework.web.bind.annotation.GetMapping("/host/ping")
            String ping() {
                return "pong";
            }
        }
    }

    private static final com.nimbusds.jose.jwk.RSAKey KEY;

    static {
        try {
            KEY = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test").generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token as the host's IdP would issue it. */
    private static String token(String subject, String scope) {
        try {
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(subject).issuer("https://idp.test")
                    .claim("scope", scope).expirationTime(new java.util.Date(System.currentTimeMillis() + 600_000))
                    .build();
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                    com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bearer(String user) {
        return user.startsWith("admin") ? token(user, "openid dai.admin") : token(user, "openid");
    }

    @BeforeAll
    static void start() {
        POSTGRES.start();
        SpringApplication app = new SpringApplication(HostApp.class);
        app.setDefaultProperties(Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "dynamic.ai.agent.environment.tier", "DEV",
                "dynamic.ai.agent.store.validate-schema", "true",
                "dynamic.ai.agent.environment.application-name", "host-it",
                "dynamic.ai.agent.security.static-role-mappings[0].source", "AUTHORITY",
                "dynamic.ai.agent.security.static-role-mappings[0].match-value", "SCOPE_dai.admin",
                "dynamic.ai.agent.security.static-role-mappings[0].role", "PLATFORM_ADMIN"));
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.SERVLET);
        // a mock servlet environment, as @SpringBootTest uses: no embedded server needed
        app.setApplicationContextFactory(type -> new org.springframework.web.context.support.GenericWebApplicationContext(
                new org.springframework.mock.web.MockServletContext()));
        var captured = new java.util.concurrent.atomic.AtomicReference<
                org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport>();
        app.addInitializers(ctx -> captured.set(org.springframework.boot.autoconfigure.condition
                .ConditionEvaluationReport.get(ctx.getBeanFactory())));
        try {
            context = app.run();
        } catch (RuntimeException e) {
            // no logging backend in tests: print why the library's conditional beans did not match
            var report = captured.get();
            report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
                if (source.contains("springaimcpservercommon") && !outcomes.isFullMatch()) {
                    outcomes.forEach(o -> System.out.println("DAI-CONDITION " + source + " -> "
                            + o.getOutcome().getMessage()));
                }
            });
            throw e;
        }
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class))
                .build();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @Test
    void theStoreIsMigratedInTheHostsDatabase() {
        assertThat(context.getBeanNamesForType(com.springaimcpservercommon.persistence.unit.DaiStore.class))
                .isNotEmpty();
    }

    @Test
    void theAdminApiAnswersAnAuthenticatedCaller() throws Exception {
        String me = mvc.perform(get("/dynamic-ai/admin/api/v1/me").header("Authorization", bearer("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(me).contains("PLATFORM_ADMIN");
    }

    @Test
    void anAnonymousCallerIsRefusedWithABearerChallengeOnEveryPlane() throws Exception {
        mvc.perform(get("/dynamic-ai/admin/api/v1/me")).andExpect(status().isUnauthorized());
        var api = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/dynamic-ai/api/agents/helper/chat").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(api.getHeader("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    void theHostsOwnEndpointsKeepTheHostsProtection() throws Exception {
        mvc.perform(get("/host/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/host/ping").header("Authorization", bearer("alice"))).andExpect(status().isOk());
        // the host's chain, not ours, still applies CSRF to the host's own state-changing requests
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/host/ping")
                .with(r -> { r.setRemoteUser(null); return r; })).andExpect(status().is4xxClientError());
    }

    @Test
    void theLibrarysChainsAreRegisteredAfterTheHosts() {
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
                .contains("hostChain", "daiAdminSecurityFilterChain", "daiMcpSecurityFilterChain",
                        "daiApiSecurityFilterChain");
    }

    // ─── the product flow, over HTTP only ─────────────────────────────────────────────────────────────────

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .build();

    /** Performs a call; returns the response, failing with the body when the status is not the expected one. */
    private static tools.jackson.databind.JsonNode call(String method, String path, String user, Object body,
                                                          int expected) throws Exception {
        return call(method, path, user, body, expected, null);
    }

    /** Same, with {@code If-Match} (the API's optimistic concurrency on state transitions). */
    private static tools.jackson.databind.JsonNode call(String method, String path, String user, Object body,
                                                          int expected, Long rowVersion) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), path)
                .header("Authorization", bearer(user));
        if (rowVersion != null) {
            request.header("If-Match", "\"" + rowVersion + "\"");
        }
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString();
        assertThat(response.getStatus()).as("%s %s as %s -> %s", method, path, user, text).isEqualTo(expected);
        return text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
    }

    @Test
    void anAgentIsAuthoredApprovedPublishedAndThenAnswersAUser() throws Exception {
        var workspace = call("POST", "/dynamic-ai/admin/api/v1/workspaces", "admin",
                Map.of("slug", "support", "name", "Support"), 201);
        String ws = workspace.get("id").asString();
        // platform admins run the platform; authoring is a workspace role (segregation of duties)
        String author = call("GET", "/dynamic-ai/admin/api/v1/me", "admin", null, 200)
                .get("principal").get("principalId").asString();
        String reviewer = call("GET", "/dynamic-ai/admin/api/v1/me", "admin2", null, 200)
                .get("principal").get("principalId").asString();
        String members = "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members";
        call("POST", members, "admin", Map.of("principalId", author, "role", "AUTHOR"), 201);
        // the author may also approve in general, just never their own revision
        call("POST", members, "admin", Map.of("principalId", author, "role", "APPROVER"), 201);
        call("POST", members, "admin", Map.of("principalId", reviewer, "role", "APPROVER"), 201);

        String spec = "{\"displayName\":\"Helper\",\"systemPrompt\":\"You help.\","
                + "\"model\":{\"providerId\":\"openai\",\"modelName\":\"scripted\"}}";
        var created = call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/resources", "admin",
                Map.of("kind", "AGENT", "slug", "helper", "specJson", spec, "changeSummary", "first"), 201);
        String resource = created.get("resourceId").asString();
        String revision = created.get("id").asString();
        String base = "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/resources/" + resource + "/revisions/" + revision;

        long version = created.get("rowVersion").asLong();
        version = call("POST", base + ":submit", "admin", Map.of(), 200, version).get("rowVersion").asLong();
        var self = call("POST", base + ":approve", "admin", Map.of("comment", "self"), 403, version);
        assertThat(self.get("detail").asString()).contains("Separation of duties");
        version = call("POST", base + ":approve", "admin2", Map.of("comment", "ok"), 200, version)
                .get("rowVersion").asLong();
        call("POST", base + ":publish", "admin", Map.of(), 200, version);

        String alice = call("GET", "/dynamic-ai/admin/api/v1/me", "alice", null, 200)
                .get("principal").get("principalId").asString();
        call("POST", members, "admin", Map.of("principalId", alice, "role", "CONSUMER"), 201);
        // default deny: consumers hold nothing until granted
        call("POST", "/dynamic-ai/api/agents/helper/chat", "alice", Map.of("message", "hi"), 403);
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/grants", "admin", Map.of("principalId", alice,
                "permission", "agent:invoke", "targetType", "RESOURCE", "resourceId", resource), 201);

        var answer = call("POST", "/dynamic-ai/api/agents/helper/chat", "alice", Map.of("message", "hi"), 200);
        assertThat(answer.toString()).contains("Hello from the model");
    }
}
