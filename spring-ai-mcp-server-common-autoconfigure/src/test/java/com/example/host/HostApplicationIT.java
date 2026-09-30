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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}pw").roles("USER").build(),
                    User.withUsername("admin").password("{noop}pw").roles("ADMIN").build());
        }

        @Bean
        SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .build();
        }
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
                "dynamic.ai.agent.environment.application-name", "host-it",
                "dynamic.ai.agent.security.static-role-mappings[0].source", "AUTHORITY",
                "dynamic.ai.agent.security.static-role-mappings[0].match-value", "ROLE_ADMIN",
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

    private static String basic(String user) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":pw").getBytes());
    }

    @Test
    void theStoreIsMigratedInTheHostsDatabase() {
        assertThat(context.getBeanNamesForType(com.springaimcpservercommon.persistence.unit.DaiStore.class))
                .isNotEmpty();
    }

    @Test
    void theAdminApiAnswersAnAuthenticatedCaller() throws Exception {
        String me = mvc.perform(get("/dynamic-ai/admin/api/v1/me").header("Authorization", basic("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(me).contains("admin");
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mvc.perform(get("/dynamic-ai/admin/api/v1/me")).andExpect(status().isUnauthorized());
    }
}
