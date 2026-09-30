package com.springaimcpservercommon.autoconfigure;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import com.springaimcpservercommon.security.port.ApiKeyLookup;
import com.springaimcpservercommon.security.apikey.FailedAttemptLimiter;
import com.springaimcpservercommon.security.apikey.ApiKeyService;
import com.springaimcpservercommon.security.apikey.ApiKeyPepperProvider;
import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationFilter;
import com.springaimcpservercommon.security.web.DynamicAiHttpSecurityConfigurer;
import com.springaimcpservercommon.security.web.DynamicAiSecurityOptions;
import com.springaimcpservercommon.security.web.HostAuthenticationCapabilities;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The library's own security filter chains for {@code /dynamic-ai/**} (SEC-01 §8): admin (host session login or
 * bearer tokens, CSRF for sessions), MCP (stateless bearer) and API (stateless bearer). Each chain is scoped with a
 * {@code securityMatcher}, so the host's chains keep everything else; authentication is the host's own (its
 * {@code JwtDecoder} or opaque-token introspector, its session login).
 *
 * <p>Ordered <em>after</em> Boot's servlet security auto-configuration on purpose: Boot creates its default
 * catch-all chain only when the host defines none, and if our chains existed first a host relying on that default
 * would lose it for the rest of the application. Hosts that want to assemble the chains themselves set
 * {@code dynamic.ai.agent.security.filter-chains.enabled=false} and use {@link DynamicAiHttpSecurityConfigurer}.
 *
 * <p>API keys are not offered yet (OQ-37): the key service has no auto-configuration.
 */
@NullMarked
@AutoConfiguration(after = DaiCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration")
@ConditionalOnProperty(prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.security.filter-chains", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpSecurity.class, SecurityFilterChain.class, DynamicAiHttpSecurityConfigurer.class})
@ConditionalOnBean(HttpSecurity.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(DaiApiKeyProperties.class)
public class DaiWebSecurityAutoConfiguration {

    private static final String JWT_DECODER = "org.springframework.security.oauth2.jwt.JwtDecoder";
    private static final String OPAQUE_INTROSPECTOR =
            "org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector";
    private static final String CLIENT_REGISTRATIONS =
            "org.springframework.security.oauth2.client.registration.ClientRegistrationRepository";

    /**
     * What the host's security offers, found in its bean factory.
     *
     * @param beans the bean factory
     * @return the capabilities
     */
    @Bean
    @ConditionalOnMissingBean
    HostAuthenticationCapabilities daiHostAuthenticationCapabilities(ListableBeanFactory beans) {
        Object decoder = single(beans, JWT_DECODER);
        Object introspector = single(beans, OPAQUE_INTROSPECTOR);
        Object registrations = single(beans, CLIENT_REGISTRATIONS);
        List<String> registrationIds = new ArrayList<>();
        if (registrations instanceof Iterable<?> iterable) {
            for (Object registration : iterable) {
                if (registration instanceof org.springframework.security.oauth2.client.registration.ClientRegistration r) {
                    registrationIds.add(r.getRegistrationId());
                }
            }
        }
        // a session login other than OAuth2 (form, LDAP, SAML) cannot be detected reliably; assume the host has one,
        // which only means that unauthenticated browser navigation to the admin UI is sent to /login
        return new HostAuthenticationCapabilities(decoder, introspector, registrations != null, registrationIds, true);
    }

    private static @Nullable Object single(ListableBeanFactory beans, String typeName) {
        ClassLoader loader = DaiWebSecurityAutoConfiguration.class.getClassLoader();
        if (!ClassUtils.isPresent(typeName, loader)) {
            return null;
        }
        Class<?> type = ClassUtils.resolveClassName(typeName, loader);
        String[] names = beans.getBeanNamesForType(type);
        return names.length == 1 ? beans.getBean(names[0]) : null;
    }

    /**
     * Chain settings: base path {@code /dynamic-ai}, no API keys yet, stateless data plane, MCP challenge
     * advertising the protected-resource metadata when {@code dynamic.ai.agent.mcp.resource-uri} is set.
     *
     * @param props framework properties
     * @return the options
     */
    @Bean
    @ConditionalOnMissingBean
    DynamicAiSecurityOptions daiSecurityOptions(DaiProperties props, DaiApiKeyProperties apiKeys) {
        String resource = props.mcp().resourceUri();
        String metadata = resource == null ? null
                : resource.replaceFirst("^(https?://[^/]+)(.*)$", "$1/.well-known/oauth-protected-resource$2");
        return new DynamicAiSecurityOptions("/dynamic-ai", apiKeys.enabled(), apiKeys.acceptDedicatedHeader(), false,
                null, DynamicAiSecurityOptions.DEFAULT_ADMIN_CSP, metadata, List.of("dai.mcp.read"));
    }

    /**
     * Hashing secret from {@code dynamic.ai.agent.security.api-keys.pepper}. Declare your own
     * {@link ApiKeyPepperProvider} to rotate or to read it from a vault.
     *
     * @param props API key settings
     * @return the provider
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyPepperProvider.class)
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.security.api-keys", name = "enabled", havingValue = "true")
    ApiKeyPepperProvider daiApiKeyPepperProvider(DaiApiKeyProperties props) {
        if (props.pepper() == null || props.pepper().isBlank()) {
            throw new IllegalStateException("dynamic.ai.agent.security.api-keys.enabled=true needs "
                    + "dynamic.ai.agent.security.api-keys.pepper (Base64, >= 32 bytes) or an ApiKeyPepperProvider bean");
        }
        byte[] decoded;
        try {
            decoded = java.util.Base64.getDecoder().decode(props.pepper().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("dynamic.ai.agent.security.api-keys.pepper is not valid Base64");
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("dynamic.ai.agent.security.api-keys.pepper must decode to >= 32 bytes");
        }
        int version = props.pepperVersion();
        return new ApiKeyPepperProvider() {
            @Override
            public int currentVersion() {
                return version;
            }

            @Override
            public byte[] pepper(int requested) {
                if (requested != version) {
                    throw new IllegalArgumentException("unknown pepper version " + requested);
                }
                return decoded.clone();
            }
        };
    }

    /**
     * Generates and verifies API keys. Needs the store (the persistence auto-configuration) and a pepper.
     *
     * @param lookup  the key store port
     * @param peppers the pepper provider
     * @param props   API key settings
     * @param core    framework settings (environment tier)
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyService.class)
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.security.api-keys", name = "enabled", havingValue = "true")
    ApiKeyService daiApiKeyService(ObjectProvider<ApiKeyLookup> lookup, ApiKeyPepperProvider peppers,
                                   DaiApiKeyProperties props, DaiProperties core) {
        ApiKeyLookup store = lookup.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("dynamic.ai.agent.security.api-keys.enabled=true needs the dynamic_ai "
                    + "store (a DataSource and dynamic.ai.agent.store.*)");
        }
        String environment = props.keyEnvironment() != null ? props.keyEnvironment()
                : core.environment().tier().toLowerCase(java.util.Locale.ROOT);
        return new ApiKeyService(store, peppers, environment, java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5));
    }

    /**
     * The configurer the chains are built with.
     *
     * @param options chain settings
     * @param host    the host's authentication
     * @param service API key service, when API keys are enabled
     * @param props   API key settings
     * @return the configurer
     */
    @Bean
    @ConditionalOnMissingBean
    DynamicAiHttpSecurityConfigurer daiHttpSecurityConfigurer(DynamicAiSecurityOptions options,
                                                              HostAuthenticationCapabilities host,
                                                              ObjectProvider<ApiKeyService> service,
                                                              DaiApiKeyProperties props) {
        ApiKeyAuthenticationFilter filter = !options.apiKeysEnabled() ? null : new ApiKeyAuthenticationFilter(
                Objects.requireNonNull(service.getIfAvailable(), "ApiKeyService"),
                FailedAttemptLimiter.defaults(java.time.Clock.systemUTC()), props.acceptDedicatedHeader());
        return new DynamicAiHttpSecurityConfigurer(options, host, filter);
    }

    /**
     * Admin plane chain ({@code /dynamic-ai/admin/**}).
     *
     * @param http       the builder
     * @param configurer the configurer
     * @return the chain
     * @throws Exception from the builder
     */
    @Bean
    @Order(DynamicAiHttpSecurityConfigurer.ADMIN_CHAIN_ORDER)
    @ConditionalOnMissingBean(name = "daiAdminSecurityFilterChain")
    SecurityFilterChain daiAdminSecurityFilterChain(HttpSecurity http, DynamicAiHttpSecurityConfigurer configurer)
            throws Exception {
        configurer.configureAdminChain(http);
        return http.build();
    }

    /**
     * MCP chain ({@code /dynamic-ai/mcp}).
     *
     * @param http       the builder
     * @param configurer the configurer
     * @return the chain
     * @throws Exception from the builder
     */
    @Bean
    @Order(DynamicAiHttpSecurityConfigurer.MCP_CHAIN_ORDER)
    @ConditionalOnMissingBean(name = "daiMcpSecurityFilterChain")
    SecurityFilterChain daiMcpSecurityFilterChain(HttpSecurity http, DynamicAiHttpSecurityConfigurer configurer)
            throws Exception {
        configurer.configureMcpChain(http);
        return http.build();
    }

    /**
     * Data plane chain (everything else under {@code /dynamic-ai}).
     *
     * @param http       the builder
     * @param configurer the configurer
     * @return the chain
     * @throws Exception from the builder
     */
    @Bean
    @Order(DynamicAiHttpSecurityConfigurer.API_CHAIN_ORDER)
    @ConditionalOnMissingBean(name = "daiApiSecurityFilterChain")
    SecurityFilterChain daiApiSecurityFilterChain(HttpSecurity http, DynamicAiHttpSecurityConfigurer configurer)
            throws Exception {
        configurer.configureApiChain(http);
        return http.build();
    }
}
