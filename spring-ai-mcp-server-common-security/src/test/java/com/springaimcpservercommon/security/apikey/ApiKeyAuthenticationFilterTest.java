package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.TestFixtures.MutableClock;
import com.springaimcpservercommon.security.principal.ServiceAccountAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private ApiKeyService service;
    private ApiKeyAuthenticationFilter filter;
    private GeneratedApiKey key;
    private final AtomicReference<Authentication> seen = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        MutableClock clock = new MutableClock(NOW);
        ApiKeyTestSupport.Store store = new ApiKeyTestSupport.Store();
        service = new ApiKeyService(store, new ApiKeyTestSupport.Peppers(), "prod", clock, Duration.ZERO, Duration.ofMinutes(5));
        filter = new ApiKeyAuthenticationFilter(service, new FailedAttemptLimiter(3, Duration.ofMinutes(1), 100, clock), true);
        key = service.generate(NOW.plus(Duration.ofDays(10)));
        store.save(key, Set.of("tool:invoke"), List.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dynamic-ai/api/x");
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    @Test
    void requestsWithoutKeyPassThroughUnauthenticated() throws Exception {
        MockHttpServletResponse response = run(request());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNull();
    }

    @Test
    void validKeyInAuthorizationHeaderAuthenticatesTheServiceAccount() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "ApiKey " + key.plaintext());
        run(request);
        assertThat(seen.get()).isInstanceOfSatisfying(ApiKeyAuthenticationToken.class, token -> {
            assertThat(token.isAuthenticated()).isTrue();
            assertThat(token.getCredentials()).isNull();
            assertThat(token.keyPrefix()).isEqualTo(key.keyPrefix());
            assertThat(((ServiceAccountAuthentication) token).serviceAccount().permissions()).containsExactly("tool:invoke");
        });
    }

    @Test
    void dedicatedHeaderIsAcceptedWhenEnabled() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, key.plaintext());
        run(request);
        assertThat(seen.get()).isInstanceOf(ApiKeyAuthenticationToken.class);
    }

    @Test
    void invalidKeyGetsGeneric401WithoutDetails() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "ApiKey " + key.plaintext() + "x");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("ApiKey realm=\"dynamic-ai\"");
        assertThat(response.getContentAsString()).contains("unauthenticated").doesNotContain("MALFORMED", "MISMATCH");
        assertThat(seen.get()).isNull();
    }

    @Test
    void apiKeyTogetherWithBearerIsRejectedAsAmbiguous() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, key.plaintext());
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(seen.get()).isNull();
    }

    @Test
    void repeatedFailuresAreRateLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest bad = request();
            bad.addHeader("Authorization", "ApiKey dai_prod_AAAAAAAAAAAA_" + "x".repeat(43));
            assertThat(run(bad).getStatus()).isEqualTo(401);
        }
        MockHttpServletRequest good = request();
        good.addHeader("Authorization", "ApiKey " + key.plaintext());
        MockHttpServletResponse response = run(good);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();

        MockHttpServletRequest otherClient = request();
        otherClient.setRemoteAddr("198.51.100.1");
        otherClient.addHeader("Authorization", "ApiKey " + key.plaintext());
        run(otherClient);
        assertThat(seen.get()).isInstanceOf(ApiKeyAuthenticationToken.class);
    }
}
