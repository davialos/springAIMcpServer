package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationFilter;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Objects;

/**
 * Applies our security to {@link HttpSecurity} builders; autoconfigure creates the actual {@code SecurityFilterChain}
 * beans with it (SEC-01 §8). Three chains, all scoped with {@code securityMatcher} so the host's chains are untouched
 * (LLD-12 §4), and ordered before the host's catch-all chain:
 *
 * <table>
 *   <caption>Chains</caption>
 *   <tr><th>Chain</th><th>Matcher</th><th>Order</th><th>Sessions / CSRF</th><th>Authentication reused from host</th></tr>
 *   <tr><td>admin</td><td>{@code {base}/admin/**}</td><td>{@link #ADMIN_CHAIN_ORDER}</td>
 *       <td>host session, CSRF ({@code spa()}; exempt for bearer requests)</td>
 *       <td>host session login (oauth2Login/form/LDAP/SAML) + resource server if a decoder exists</td></tr>
 *   <tr><td>mcp</td><td>{@code {base}/mcp}, {@code {base}/mcp/**}</td><td>{@link #MCP_CHAIN_ORDER}</td>
 *       <td>stateless, no CSRF (header credentials only)</td><td>resource server; API keys</td></tr>
 *   <tr><td>api</td><td>{@code {base}/**}</td><td>{@link #API_CHAIN_ORDER}</td>
 *       <td>stateless (or host session + CSRF if {@code dataPlaneSessions})</td><td>resource server; API keys</td></tr>
 * </table>
 *
 * Every chain requires an authenticated caller; resource-level decisions are made by the authorization engine.
 * Headers: {@code X-Content-Type-Options}, no-store cache control, {@code X-Frame-Options: DENY}, CSP with
 * {@code frame-ancestors 'none'} (strict CSP on the admin UI), restrictive {@code Referrer-Policy}; HSTS as Spring
 * Security's default (HTTPS only). The admin chain never adds a login page of its own.
 *
 * <p>The {@link ApiKeyAuthenticationFilter} is added inside the chains only; autoconfigure must not expose it as a
 * plain {@code Filter} bean (Spring Boot would register it for every request) — or must disable that registration.
 */
public final class DynamicAiHttpSecurityConfigurer {

    /** Order of the admin chain (SEC-01 §8: {@code HIGHEST_PRECEDENCE + 50}). */
    public static final int ADMIN_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 50;
    /** Order of the MCP chain. */
    public static final int MCP_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 51;
    /** Order of the API (data plane) chain, which matches everything else under the base path. */
    public static final int API_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 52;

    private static final boolean RESOURCE_SERVER_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken",
            DynamicAiHttpSecurityConfigurer.class.getClassLoader());

    private final DynamicAiSecurityOptions options;
    private final HostAuthenticationCapabilities host;
    private final @Nullable ApiKeyAuthenticationFilter apiKeyFilter;
    private final AccessDeniedHandler deniedHandler = new DaiAccessDeniedHandler();

    /**
     * Creates the configurer.
     *
     * @param options      chain settings
     * @param host         detected host authentication
     * @param apiKeyFilter API key filter, or {@code null} when API keys are disabled
     */
    public DynamicAiHttpSecurityConfigurer(DynamicAiSecurityOptions options, HostAuthenticationCapabilities host,
                                           @Nullable ApiKeyAuthenticationFilter apiKeyFilter) {
        this.options = Objects.requireNonNull(options, "options");
        this.host = Objects.requireNonNull(host, "host");
        this.apiKeyFilter = options.apiKeysEnabled() ? apiKeyFilter : null;
        if (options.apiKeysEnabled() && apiKeyFilter == null) {
            throw new IllegalArgumentException("API keys are enabled but no ApiKeyAuthenticationFilter was given");
        }
    }

    /**
     * Whether bearer tokens are accepted (host has a decoder/introspector and the resource-server module is present).
     *
     * @return {@code true} if the resource server is enabled on our chains
     */
    public boolean bearerTokensEnabled() {
        return RESOURCE_SERVER_PRESENT && host.bearerTokens();
    }

    /**
     * The admin chain as a customizer.
     *
     * @return customizer
     */
    public Customizer<HttpSecurity> adminChain() {
        return this::configureAdminChain;
    }

    /**
     * The MCP chain as a customizer.
     *
     * @return customizer
     */
    public Customizer<HttpSecurity> mcpChain() {
        return this::configureMcpChain;
    }

    /**
     * The API chain as a customizer.
     *
     * @return customizer
     */
    public Customizer<HttpSecurity> apiChain() {
        return this::configureApiChain;
    }

    /**
     * Configures the admin (control plane) chain.
     *
     * @param http builder
     */
    public void configureAdminChain(HttpSecurity http) {
        AuthenticationEntryPoint entryPoint = new AdminLoginEntryPoint(adminLoginUrl());
        http.securityMatcher(options.adminPattern())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(new CredentialHeaderRequestMatcher()))
                .headers(h -> commonHeaders(h, options.adminContentSecurityPolicy(), ReferrerPolicy.SAME_ORIGIN))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        if (bearerTokensEnabled()) {
            ResourceServerSupport.apply(http, host, new DaiAuthenticationEntryPoint(true, false, null, List.of()),
                    deniedHandler);
        }
    }

    /**
     * Configures the MCP chain: stateless, bearer tokens (RFC 9728 challenge) and API keys only.
     *
     * @param http builder
     */
    public void configureMcpChain(HttpSecurity http) {
        AuthenticationEntryPoint entryPoint = new DaiAuthenticationEntryPoint(bearerTokensEnabled(), apiKeyFilter != null,
                options.mcpResourceMetadataUrl(), options.mcpChallengeScopes());
        http.securityMatcher(options.mcpPatterns())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(h -> commonHeaders(h, DynamicAiSecurityOptions.API_CSP, ReferrerPolicy.NO_REFERRER))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        tokenAuthentication(http, entryPoint);
    }

    /**
     * Configures the API (data plane) chain.
     *
     * @param http builder
     */
    public void configureApiChain(HttpSecurity http) {
        AuthenticationEntryPoint entryPoint = new DaiAuthenticationEntryPoint(bearerTokensEnabled(), apiKeyFilter != null,
                null, List.of());
        http.securityMatcher(options.allPattern());
        if (options.dataPlaneSessions()) {
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                    .csrf(csrf -> csrf.spa().ignoringRequestMatchers(new CredentialHeaderRequestMatcher()));
        } else {
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.disable());
        }
        http.headers(h -> commonHeaders(h, DynamicAiSecurityOptions.API_CSP, ReferrerPolicy.NO_REFERRER))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        tokenAuthentication(http, entryPoint);
    }

    private void tokenAuthentication(HttpSecurity http, AuthenticationEntryPoint entryPoint) {
        if (bearerTokensEnabled()) {
            ResourceServerSupport.apply(http, host, entryPoint, deniedHandler);
        }
        if (apiKeyFilter != null) {
            http.addFilterBefore(apiKeyFilter, BasicAuthenticationFilter.class);
        }
    }

    private @Nullable String adminLoginUrl() {
        if (options.adminLoginUrl() != null) {
            return options.adminLoginUrl();
        }
        if (host.oauth2Login() && host.loginRegistrationIds().size() == 1) {
            return "/oauth2/authorization/" + host.loginRegistrationIds().getFirst();
        }
        return host.sessionLogin() || host.oauth2Login() ? "/login" : null;
    }

    private static void commonHeaders(HeadersConfigurer<HttpSecurity> headers, String csp, ReferrerPolicy referrer) {
        headers.contentTypeOptions(Customizer.withDefaults())
                .cacheControl(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(c -> c.policyDirectives(csp))
                .referrerPolicy(r -> r.policy(referrer));
    }
}
