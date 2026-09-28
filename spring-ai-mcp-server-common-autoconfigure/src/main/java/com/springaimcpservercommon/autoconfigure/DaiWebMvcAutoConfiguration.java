package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.webmvc.endpoint.DynamicEndpointRegistrar;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/**
 * Auto-configuration for the dynamic REST endpoint engine (LLD-04).
 *
 * <p>Activated when {@link GenericDynamicHandler} is on the classpath (webmvc module present)
 * and Spring MVC's {@link RequestMappingHandlerMapping} is available.
 *
 * <p>All beans are {@link ConditionalOnMissingBean}; hosts may replace any component.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiSecurityAutoConfiguration.class,
                             WebMvcAutoConfiguration.class})
@ConditionalOnClass({GenericDynamicHandler.class, RequestMappingHandlerMapping.class})
@NullMarked
public class DaiWebMvcAutoConfiguration {

    /**
     * Default principal resolver: uses the registered {@link AuthorityMapper} to map the
     * Spring Security {@link org.springframework.security.core.context.SecurityContextHolder} authentication
     * to a {@link com.springaimcpservercommon.core.principal.DaiPrincipal}.
     *
     * @param authorityMapper the authority mapper (registered by {@link DaiSecurityAutoConfiguration})
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(GenericDynamicHandler.DaiPrincipalResolver.class)
    @ConditionalOnBean(AuthorityMapper.class)
    public GenericDynamicHandler.DaiPrincipalResolver principalResolver(AuthorityMapper authorityMapper) {
        return request -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                throw new SecurityException("No authenticated principal in SecurityContext");
            }
            return authorityMapper.map(auth);
        };
    }

    /**
     * Default kill-switch checker: all endpoints are enabled by default.
     * Replace with a persistence-backed view for runtime kill-switch support.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(GenericDynamicHandler.KillSwitchChecker.class)
    public GenericDynamicHandler.KillSwitchChecker endpointKillSwitchChecker() {
        return endpointId -> false; // false = NOT active (endpoint is enabled)
    }

    /**
     * Default rate limiter: no rate limiting enforced.
     * Replace with a persistence-backed or Redis-backed implementation for production deployments.
     *
     * @return the rate limiter
     */
    @Bean
    @ConditionalOnMissingBean(GenericDynamicHandler.RateLimiter.class)
    public GenericDynamicHandler.RateLimiter rateLimiter() {
        return (principal, endpointId) -> -1; // -1 = allowed
    }

    /**
     * Default backing executor: placeholder that returns an empty JSON array.
     * Replace with a real implementation that dispatches to queries, agents, and operations.
     *
     * @return the executor
     */
    @Bean
    @ConditionalOnMissingBean(GenericDynamicHandler.BackingExecutor.class)
    public GenericDynamicHandler.BackingExecutor backingExecutor() {
        return (def, principal, params) -> "[]";
    }

    /**
     * The single handler for all dynamic routes.
     *
     * @param registrar           route table
     * @param principalResolver   maps the request to a {@link com.springaimcpservercommon.core.principal.DaiPrincipal}
     * @param authorizationEngine authorization decision point
     * @param backingExecutor     executes the endpoint backing
     * @param killSwitchChecker   checks the endpoint kill switch
     * @param rateLimiter         per-principal rate limit enforcement
     * @param observationRegistry Micrometer registry for metrics
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({GenericDynamicHandler.DaiPrincipalResolver.class,
                        AuthorizationEngine.class,
                        GenericDynamicHandler.BackingExecutor.class})
    public GenericDynamicHandler genericDynamicHandler(
            DynamicEndpointRegistrar registrar,
            GenericDynamicHandler.DaiPrincipalResolver principalResolver,
            AuthorizationEngine authorizationEngine,
            GenericDynamicHandler.BackingExecutor backingExecutor,
            GenericDynamicHandler.KillSwitchChecker killSwitchChecker,
            GenericDynamicHandler.RateLimiter rateLimiter,
            ObservationRegistry observationRegistry) {
        return new GenericDynamicHandler(registrar, principalResolver, authorizationEngine,
                backingExecutor, killSwitchChecker, rateLimiter, observationRegistry);
    }

    /**
     * The dynamic endpoint registrar that manages Spring MVC route registration / deregistration.
     *
     * @param handlerMapping the host's primary {@link RequestMappingHandlerMapping}
     * @param handler        the generic dynamic handler
     * @return the registrar
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    public DynamicEndpointRegistrar dynamicEndpointRegistrar(RequestMappingHandlerMapping handlerMapping,
                                                              GenericDynamicHandler handler) throws NoSuchMethodException {
        Method handleMethod = GenericDynamicHandler.class.getMethod(
                "handleRequest",
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);
        return new DynamicEndpointRegistrar(handlerMapping, handler, handleMethod);
    }
}
