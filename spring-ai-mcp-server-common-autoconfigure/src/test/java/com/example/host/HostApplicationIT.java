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
                    var last = prompt.getInstructions().getLast();
                    if (last instanceof org.springframework.ai.chat.messages.ToolResponseMessage tr) {
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(
                                "Tool said: " + tr.getResponses().getFirst().responseData()))));
                    }
                    String text = last.getText() == null ? "" : last.getText();
                    if (text.startsWith("findbig")) {
                        return toolCall("find_orders", "{\"customerId\":\"c-evil\",\"limit\":500}");
                    }
                    if (text.startsWith("kb")) {
                        // echo what the agent's system prompt carries, to show what reached the model
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(
                                prompt.getSystemMessage().getText()))));
                    }
                    if (text.startsWith("myorders")) {
                        return toolCall("find_my_orders", "{\"status\":\"OPEN\"}");
                    }
                    if (text.startsWith("find")) {
                        return toolCall("find_orders", "{\"customerId\":\"c-evil\",\"limit\":3}");
                    }
                    if (text.startsWith("cancel")) {
                        return toolCall("cancel_order", "{\"orderId\":\"o1\"}");
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Hello from the model"))));
                }

                private ChatResponse toolCall(String name, String args) {
                    var call = new AssistantMessage.ToolCall("call-1", "function", name, args);
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                            .toolCalls(List.of(call)).build())));
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
                    .claim("scope", scope).claim("customerId", "c-" + subject).claim("azp", "mcp-app").expirationTime(new java.util.Date(System.currentTimeMillis() + 600_000))
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
        if (user.endsWith("-mcp")) {
            return token(user.substring(0, user.length() - 4), "openid dai.mcp.read");
        }
        return user.startsWith("admin") ? token(user, "openid dai.admin") : token(user, "openid");
    }

    @BeforeAll
    static void start() {
        POSTGRES.start();
        SpringApplication app = new SpringApplication(HostApp.class);
        var props = new java.util.HashMap<String, Object>();
        props.put("spring.jpa.hibernate.ddl-auto", "create");
        props.put("dynamic.ai.agent.write.enabled", "true");
        props.put("dynamic.ai.agent.mcp.enabled", "true");
        props.put("dynamic.ai.agent.security.attribute-claims.customerId", "customerId");
        props.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        props.put("spring.datasource.username", POSTGRES.getUsername());
        props.put("spring.datasource.password", POSTGRES.getPassword());
        props.put("dynamic.ai.agent.environment.tier", "DEV");
        props.put("dynamic.ai.agent.store.validate-schema", "true");
        props.put("dynamic.ai.agent.environment.application-name", "host-it");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].source", "AUTHORITY");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].match-value", "SCOPE_dai.admin");
        props.put("dynamic.ai.agent.security.static-role-mappings[0].role", "PLATFORM_ADMIN");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].source", "AUTHORITY");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].match-value", "SCOPE_dai.admin");
        props.put("dynamic.ai.agent.security.static-role-mappings[1].role", "SECURITY_ADMIN");
        app.setDefaultProperties(props);

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

    // ─── annotated host code becomes safe AI tools ────────────────────────────────────────────────────────

    /** Creates a workspace with an author (admin), and approvers (admin, admin2); returns its id. */
    private static String workspaceWithTeam(String slug) throws Exception {
        String ws = call("POST", "/dynamic-ai/admin/api/v1/workspaces", "admin",
                Map.of("slug", slug, "name", slug), 201).get("id").asString();
        String members = "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members";
        String author = principalId("admin");
        String reviewer = principalId("admin2");
        call("POST", members, "admin", Map.of("principalId", author, "role", "AUTHOR"), 201);
        call("POST", members, "admin", Map.of("principalId", reviewer, "role", "APPROVER"), 201);
        return ws;
    }

    private static String principalId(String user) throws Exception {
        return call("GET", "/dynamic-ai/admin/api/v1/me", user, null, 200).get("principal").get("principalId")
                .asString();
    }

    /** Authors, reviews (by someone else) and publishes a resource; returns its resource id. */
    private static String publish(String ws, String kind, String slug, String spec) throws Exception {
        var created = call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/resources", "admin",
                Map.of("kind", kind, "slug", slug, "specJson", spec, "changeSummary", "first"), 201);
        String resource = created.get("resourceId").asString();
        String base = "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/resources/" + resource + "/revisions/"
                + created.get("id").asString();
        long version = created.get("rowVersion").asLong();
        version = call("POST", base + ":submit", "admin", Map.of(), 200, version).get("rowVersion").asLong();
        version = call("POST", base + ":approve", "admin2", Map.of("comment", "ok"), 200, version)
                .get("rowVersion").asLong();
        call("POST", base + ":publish", "admin", Map.of(), 200, version);
        return resource;
    }

    private static void grant(String ws, String user, String permission, String resource) throws Exception {
        String id = principalId(user);
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/grants", "admin", Map.of("principalId", id,
                "permission", permission, "targetType", "RESOURCE", "resourceId", resource), 201);
    }

    private static boolean seeded;

    private static synchronized void seedOrders() {
        if (seeded) {
            return;
        }
        seeded = true;
        var emf = context.getBean(jakarta.persistence.EntityManagerFactory.class);
        try (var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Order("o1", "c-alice", "OPEN", "4111111111111111"));
            em.persist(new Order("o2", "c-alice", "OPEN", "4111111111111111"));
            em.persist(new Order("o3", "c-bob", "OPEN", "5500000000000004"));
            em.getTransaction().commit();
        }
    }

    @Test
    void annotatedHostCodeBecomesAToolThatRunsAsTheCallerWithinItsConstraints() throws Exception {
        seedOrders();
        var operations = call("GET", "/dynamic-ai/admin/api/v1/catalog/operations", "admin", null, 200);
        assertThat(operations.toString()).contains("OrderService#find").contains("OrderService#cancel");
        var entities = call("GET", "/dynamic-ai/admin/api/v1/catalog/entities", "admin", null, 200);
        assertThat(entities.toString()).contains("Order");

        String ws = workspaceWithTeam("orders");
        String ref = null;
        for (var op : operations.has("items") ? operations.get("items") : operations) {
            if (op.get("ref").asString().contains("OrderService#find")) {
                ref = op.get("ref").asString();
            }
        }
        assertThat(ref).isNotNull();
        String binding = publish(ws, "TOOL_BINDING", "find-orders", JSON.writeValueAsString(Map.of(
                "toolName", "find_orders",
                "source", Map.of("kind", "operation", "ref", ref),
                "argConstraints", Map.of(
                        "customerId", Map.of("kind", "principalAttr", "attr", "customerId"),
                        "limit", Map.of("kind", "range", "min", 1, "max", 5)))));
        String agent = publish(ws, "AGENT", "orders-agent", JSON.writeValueAsString(Map.of(
                "displayName", "Orders", "systemPrompt", "You help with orders.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "tools", List.of(Map.of("bindingId", binding, "revision", 1)))));
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("alice"), "role", "CONSUMER"), 201);
        grant(ws, "alice", "agent:invoke", agent);
        grant(ws, "alice", "tool:invoke", binding);

        // the model asked for customer c-evil; the tool ran for alice's own customer id
        // (the model's out-of-range limit is refused, not silently changed)
        var big = call("POST", "/dynamic-ai/api/agents/orders-agent/chat", "alice",
                Map.of("message", "findbig orders"), 200);
        assertThat(big.toString()).contains("out_of_range").doesNotContain("o1");
        var answer = call("POST", "/dynamic-ai/api/agents/orders-agent/chat", "alice",
                Map.of("message", "find orders"), 200);
        assertThat(answer.toString()).contains("o1").contains("o2").doesNotContain("o3");
    }

    private static String operationRef(String method) throws Exception {
        var operations = call("GET", "/dynamic-ai/admin/api/v1/catalog/operations", "admin", null, 200);
        for (var op : operations.has("items") ? operations.get("items") : operations) {
            if (op.get("ref").asString().contains("OrderService#" + method)) {
                return op.get("ref").asString();
            }
        }
        throw new AssertionError("operation not in the catalog: " + method);
    }

    @Test
    void aModelNeverWritesItProposesAndAPersonConfirmsThroughTheHostMethod() throws Exception {
        seedOrders();
        String ws = workspaceWithTeam("orders-write");
        String binding = publish(ws, "TOOL_BINDING", "cancel-order", JSON.writeValueAsString(Map.of(
                "toolName", "cancel_order",
                "source", Map.of("kind", "operation", "ref", operationRef("cancel")),
                "writeMode", "PROPOSE", "change", "update", "entityIdArgument", "orderId")));
        String agent = publish(ws, "AGENT", "orders-writer", JSON.writeValueAsString(Map.of(
                "displayName", "Orders", "systemPrompt", "You help with orders.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "tools", List.of(Map.of("bindingId", binding, "revision", 1)))));
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("bob"), "role", "CONSUMER"), 201);
        grant(ws, "bob", "agent:invoke", agent);
        grant(ws, "bob", "tool:invoke", binding);
        grant(ws, "bob", "data:write-propose", binding);
        // confirming is a workspace-wide permission: it applies to the person's own proposals, whatever the tool
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/grants", "admin",
                Map.of("principalId", principalId("bob"), "permission", "data:write-confirm"), 201);

        int before = OrderService.CANCELLATIONS.get();
        var answer = call("POST", "/dynamic-ai/api/agents/orders-writer/chat", "bob",
                Map.of("message", "cancel o1"), 200);
        assertThat(answer.toString()).contains("proposed");
        // the model's turn changed nothing
        assertThat(OrderService.CANCELLATIONS.get()).isEqualTo(before);

        var proposals = call("GET", "/dynamic-ai/api/proposals", "bob", null, 200);
        String id = proposals.get("items").get(0).get("id").asString();
        assertThat(proposals.get("items").get(0).get("state").asString()).isEqualTo("PROPOSED");
        var detail = call("GET", "/dynamic-ai/api/proposals/" + id, "bob", null, 200);
        // someone else cannot even see bob's proposal
        call("POST", "/dynamic-ai/api/proposals/" + id + ":confirm", "alice",
                Map.of("contentHash", detail.get("contentHash").asString()), 404);
        // no approver is required here, so the person's confirmation applies it, in this request, as them
        var applied = call("POST", "/dynamic-ai/api/proposals/" + id + ":confirm", "bob",
                Map.of("contentHash", detail.get("contentHash").asString()), 200);
        assertThat(applied.get("summary").get("state").asString()).isEqualTo("APPLIED");
        // the host's own method ran, once, and the host's data changed
        assertThat(OrderService.CANCELLATIONS.get()).isEqualTo(before + 1);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        assertThat(jdbc.queryForObject("select status from host_order where id = 'o1'", String.class))
                .isEqualTo("CANCELLED");
        // repeating the apply is harmless: the host method does not run twice
        call("POST", "/dynamic-ai/api/proposals/" + id + ":apply", "bob", null, 200);
        assertThat(OrderService.CANCELLATIONS.get()).isEqualTo(before + 1);
    }

    @Test
    void aSavedQueryOverTheHostsEntityRunsAsTheCallerAndNeverShowsSensitiveColumns() throws Exception {
        seedOrders();
        String ws = workspaceWithTeam("orders-query");
        String query = publish(ws, "QUERY", "my-orders", JSON.writeValueAsString(Map.of(
                "root", "entity:com.example.host.Order",
                "select", List.of(Map.of("path", "id"), Map.of("path", "status")),
                "where", Map.of("type", "and", "children", List.of(
                        Map.of("type", "cmp", "path", "customerId", "op", "eq",
                                "operand", Map.of("kind", "principal", "attr", "customerId")),
                        Map.of("type", "cmp", "path", "status", "op", "eq",
                                "operand", Map.of("kind", "param", "name", "status")))),
                "orderBy", List.of(Map.of("path", "id")),
                "params", List.of(Map.of("name", "status", "schema", "{\"type\":\"string\"}", "required", true)))));
        String binding = publish(ws, "TOOL_BINDING", "find-my-orders", JSON.writeValueAsString(Map.of(
                "toolName", "find_my_orders", "source", Map.of("kind", "query", "ref", query))));
        String agent = publish(ws, "AGENT", "orders-reader", JSON.writeValueAsString(Map.of(
                "displayName", "Orders", "systemPrompt", "You help with orders.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "tools", List.of(Map.of("bindingId", binding, "revision", 1)))));
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("alice"), "role", "CONSUMER"), 201);
        grant(ws, "alice", "agent:invoke", agent);
        grant(ws, "alice", "tool:invoke", binding);

        var answer = call("POST", "/dynamic-ai/api/agents/orders-reader/chat", "alice",
                Map.of("message", "myorders"), 200);
        assertThat(answer.toString()).contains("o1").contains("o2").doesNotContain("o3")
                .doesNotContain("4111").doesNotContain("cardNumber");

        // a query that selects the sensitive column can be authored, but its data never reaches the model
        String leaky = publish(ws, "QUERY", "leaky", JSON.writeValueAsString(Map.of(
                "root", "entity:com.example.host.Order",
                "select", List.of(Map.of("path", "id"), Map.of("path", "cardNumber")),
                "orderBy", List.of(Map.of("path", "id")))));
        String leakyTool = publish(ws, "TOOL_BINDING", "leaky-tool", JSON.writeValueAsString(Map.of(
                "toolName", "find_my_orders", "source", Map.of("kind", "query", "ref", leaky))));
        String leakyAgent = publish(ws, "AGENT", "orders-leaky", JSON.writeValueAsString(Map.of(
                "displayName", "Orders", "systemPrompt", "You help with orders.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "tools", List.of(Map.of("bindingId", leakyTool, "revision", 1)))));
        grant(ws, "alice", "agent:invoke", leakyAgent);
        grant(ws, "alice", "tool:invoke", leakyTool);
        var leaked = call("POST", "/dynamic-ai/api/agents/orders-leaky/chat", "alice",
                Map.of("message", "myorders"), 200);
        assertThat(leaked.toString()).doesNotContain("4111").doesNotContain("5500");
    }

    private static org.springframework.mock.web.MockHttpServletResponse mcp(String user, String workspace, String body)
            throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dynamic-ai/mcp")
                .contentType("application/json").accept("application/json", "text/event-stream").content(body);
        if (user != null) {
            request.header("Authorization", bearer(user));
        }
        if (workspace != null) {
            request.header("X-DAI-Workspace", workspace);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void anMcpClientSeesAndCallsOnlyWhatItMayThroughTheSameGuards() throws Exception {
        seedOrders();
        String ws = workspaceWithTeam("orders-mcp");
        String binding = publish(ws, "TOOL_BINDING", "mcp-find-orders", JSON.writeValueAsString(Map.of(
                "toolName", "find_orders", "mcpExposed", true,
                "source", Map.of("kind", "operation", "ref", operationRef("find")),
                "argConstraints", Map.of(
                        "customerId", Map.of("kind", "principalAttr", "attr", "customerId"),
                        "limit", Map.of("kind", "range", "min", 1, "max", 5)))));
        var anonymous = mcp(null, ws, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(anonymous.getStatus()).isEqualTo(401);
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        // default deny: a valid token from a client nobody approved is refused
        assertThat(mcp("alice", ws, list).getStatus()).isEqualTo(403);
        String clients = "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/mcp-clients";
        var registered = call("POST", clients, "admin", Map.of("issuer", "https://idp.test", "clientId", "mcp-app",
                "displayName", "Test client"), 201);
        assertThat(registered.get("status").asString()).isEqualTo("PENDING");
        assertThat(mcp("alice", ws, list).getStatus()).isEqualTo(403);
        call("POST", clients + "/" + registered.get("id").asString() + ":approve", "admin", Map.of(), 403);
        call("POST", clients + "/" + registered.get("id").asString() + ":approve", "admin2", Map.of(), 200);
        // approved client, but the token carries no MCP scope: it lists nothing
        var noScope = mcp("alice", ws, list);
        assertThat(noScope.getStatus()).isEqualTo(200);
        assertThat(noScope.getContentAsString()).doesNotContain("find_orders");
        // approved client and scope, but nothing granted: nothing listed (default deny)
        assertThat(mcp("alice-mcp", ws, list).getContentAsString()).doesNotContain("find_orders");
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("alice"), "role", "CONSUMER"), 201);
        grant(ws, "alice", "tool:invoke", binding);
        var listed = mcp("alice-mcp", ws, list);
        assertThat(listed.getStatus()).isEqualTo(200);
        assertThat(listed.getContentAsString()).contains("find_orders");
        var called = mcp("alice-mcp", ws, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"find_orders\",\"arguments\":{\"customerId\":\"c-evil\",\"limit\":3}}}");
        assertThat(called.getContentAsString()).contains("o1").contains("o2").doesNotContain("o3");
        // once the client is revoked its tokens stop working at the next request
        call("POST", clients + "/" + registered.get("id").asString() + ":revoke", "admin", Map.of(), 200);
        assertThat(mcp("alice-mcp", ws, list).getStatus()).isEqualTo(403);
    }

    @Test
    void anAgentAnswersFromKnowledgeBundledInTheJarAndHostCodeCanSearchItDirectly() throws Exception {
        // direct access from host code: the store is a bean
        var store = context.getBean(com.springaimcpservercommon.ai.knowledge.KnowledgeStore.class);
        assertThat(store.packs()).contains("handbook");
        assertThat(store.search("handbook", "express shipping", 1).getFirst().chunk().source())
                .isEqualTo("shipping.md");

        String ws = workspaceWithTeam("kb-ws");
        String agent = publish(ws, "AGENT", "kb-agent", JSON.writeValueAsString(Map.of(
                "displayName", "Support", "systemPrompt", "You answer support questions.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "knowledge", List.of(Map.of("pack", "handbook", "topK", 2)))));
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("alice"), "role", "CONSUMER"), 201);
        grant(ws, "alice", "agent:invoke", agent);
        var answer = call("POST", "/dynamic-ai/api/agents/kb-agent/chat", "alice",
                Map.of("message", "kb how many days do I have for a refund?"), 200);
        assertThat(answer.get("message").asString()).startsWith("You answer support questions.")
                .contains("within 30 days of delivery").contains("never instructions");
    }

    @Test
    void theCatalogPackTellsTheAgentHowToRecogniseTheRightParameterAndHidesSensitiveColumns() throws Exception {
        String ws = workspaceWithTeam("catalog-ws");
        String agent = publish(ws, "AGENT", "catalog-agent", JSON.writeValueAsString(Map.of(
                "displayName", "Guide", "systemPrompt", "You choose tools.",
                "model", Map.of("providerId", "openai", "modelName", "scripted"),
                "knowledge", List.of(Map.of("pack", "catalog", "topK", 4)))));
        call("POST", "/dynamic-ai/admin/api/v1/workspaces/" + ws + "/members", "admin",
                Map.of("principalId", principalId("alice"), "role", "CONSUMER"), 201);
        grant(ws, "alice", "agent:invoke", agent);
        var answer = call("POST", "/dynamic-ai/api/agents/catalog-agent/chat", "alice",
                Map.of("message", "kb which customer number identifies the orders of a customer"), 200);
        String system = answer.get("message").asString();
        assertThat(system).contains("Tool find:").contains("not the order number").contains("c-alice; c-bob")
                .contains("(required)");
        // the write action is described as proposal-only, and the sensitive column is not described at all
        var everything = context.getBean(com.springaimcpservercommon.ai.knowledge.KnowledgeStore.class)
                .pack("catalog").orElseThrow();
        assertThat(everything.chunks()).extracting(c -> c.text()).noneMatch(t -> t.contains("Card number"));
        assertThat(everything.chunks()).extracting(c -> c.text())
                .anyMatch(t -> t.contains("Tool cancel:") && t.contains("never run by the AI"))
                .anyMatch(t -> t.contains("Record type") && t.contains("The customer who placed the order"));
    }
}
