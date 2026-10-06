package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationFilter;
import com.springaimcpservercommon.security.apikey.ApiKeyPepperProvider;
import com.springaimcpservercommon.security.apikey.ApiKeyService;
import com.springaimcpservercommon.security.apikey.FailedAttemptLimiter;
import com.springaimcpservercommon.security.apikey.GeneratedApiKey;
import com.springaimcpservercommon.security.port.ApiKeyLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Builds our three chains in a real Spring Security context and checks their observable behaviour. */
@SpringJUnitWebConfig(DynamicAiHttpSecurityConfigurerTest.Config.class)
class DynamicAiHttpSecurityConfigurerTest {

    static final class KeyHolder implements ApiKeyLookup {
        ApiKeyRecord record;

        @Override
        public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
            return record != null && record.keyPrefix().equals(keyPrefix) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public void touchLastUsed(UUID apiKeyId, Instant at) {
        }
    }

    @RestController
    static class PingController {
        @GetMapping({"/dynamic-ai/api/ping", "/dynamic-ai/admin/ping", "/dynamic-ai/mcp", "/dynamic-ai/ui/chat/x.js"})
        String ping() {
            return "pong";
        }

        @PostMapping({"/dynamic-ai/admin/ping", "/dynamic-ai/ui/chat/x.js"})
        String change() {
            return "changed";
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {
        final KeyHolder keys = new KeyHolder();
        final ApiKeyService service = new ApiKeyService(keys, new ApiKeyPepperProvider() {
            @Override
            public int currentVersion() {
                return 1;
            }

            @Override
            public byte[] pepper(int version) {
                return "test-pepper-0123456789-0123456789-xyz".getBytes();
            }
        }, "test", Clock.systemUTC(), Duration.ZERO, Duration.ofMinutes(5));

        final DynamicAiHttpSecurityConfigurer configurer = new DynamicAiHttpSecurityConfigurer(
                DynamicAiSecurityOptions.defaults(),
                new HostAuthenticationCapabilities(null, null, false, List.of(), true),
                new ApiKeyAuthenticationFilter(service, FailedAttemptLimiter.defaults(Clock.systemUTC()), false));

        @Bean
        KeyHolder keyHolder() {
            return keys;
        }

        @Bean
        ApiKeyService apiKeyService() {
            return service;
        }

        @Bean
        PingController pingController() {
            return new PingController();
        }

        @Bean
        @Order(DynamicAiHttpSecurityConfigurer.ADMIN_CHAIN_ORDER)
        SecurityFilterChain admin(HttpSecurity http) throws Exception {
            configurer.configureAdminChain(http);
            return http.build();
        }

        @Bean
        @Order(DynamicAiHttpSecurityConfigurer.MCP_CHAIN_ORDER)
        SecurityFilterChain mcp(HttpSecurity http) throws Exception {
            configurer.configureMcpChain(http);
            return http.build();
        }

        @Bean
        @Order(DynamicAiHttpSecurityConfigurer.API_CHAIN_ORDER)
        SecurityFilterChain api(HttpSecurity http) throws Exception {
            configurer.configureApiChain(http);
            return http.build();
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    KeyHolder keys;
    @Autowired
    ApiKeyService service;

    MockMvc mvc;
    GeneratedApiKey key;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        key = service.generate(Instant.now().plus(Duration.ofDays(1)));
        keys.record = new ApiKeyLookup.ApiKeyRecord(UUID.randomUUID(), key.keyPrefix(), key.keyHash(), key.hashAlgorithm(),
                key.expiresAt(), null, null, UUID.randomUUID(), UUID.randomUUID(), "bot", true, UUID.randomUUID(),
                Set.of("tool:invoke"), List.of());
    }

    @Test
    void apiChainIsStatelessAndChallengesWithApiKey() throws Exception {
        mvc.perform(get("/dynamic-ai/api/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey realm=\"dynamic-ai\""))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().doesNotExist("Set-Cookie"));

        mvc.perform(get("/dynamic-ai/api/ping").header("Authorization", "ApiKey " + key.plaintext()))
                .andExpect(status().isOk());
    }

    @Test
    void chatUiAssetsAreReadableAnonymouslyButNothingElseIs() throws Exception {
        mvc.perform(get("/dynamic-ai/ui/chat/x.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(post("/dynamic-ai/ui/chat/x.js")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dynamic-ai/api/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void mcpChainAcceptsApiKeysOnly() throws Exception {
        mvc.perform(get("/dynamic-ai/mcp")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dynamic-ai/mcp").header("Authorization", "ApiKey " + key.plaintext()))
                .andExpect(status().isOk());
    }

    @Test
    void adminChainRedirectsBrowsersAndProtectsAgainstCsrf() throws Exception {
        mvc.perform(get("/dynamic-ai/admin/ping").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/dynamic-ai/admin/ping").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/dynamic-ai/admin/ping").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(post("/dynamic-ai/admin/ping").with(user("admin")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/dynamic-ai/admin/ping").with(user("admin")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void apiKeysAreNotAcceptedOnTheAdminChain() throws Exception {
        mvc.perform(get("/dynamic-ai/admin/ping").header("Authorization", "ApiKey " + key.plaintext())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
