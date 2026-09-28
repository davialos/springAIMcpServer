package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.webmvc.endpoint.AgentChatController;
import com.springaimcpservercommon.webmvc.endpoint.DispatchingBackingExecutor;
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
     * Default query backing handler: placeholder that returns an empty JSON array.
     * Replace with a persistence-backed implementation when the query module is present.
     *
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.QueryBackingHandler.class)
    public DispatchingBackingExecutor.QueryBackingHandler queryBackingHandler() {
        return (queryId, bindings, principal) -> "[]";
    }

    /**
     * Default operation backing handler: placeholder that returns an empty JSON object.
     * Replace with a host-provided implementation for real operation dispatch.
     *
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.OperationBackingHandler.class)
    public DispatchingBackingExecutor.OperationBackingHandler operationBackingHandler() {
        return (operation, bindings, principal) -> "{}";
    }

    /**
     * Default agent-definition resolver: returns {@code null} for all slugs.
     * Replace with a persistence-backed implementation when the persistence module is present.
     *
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.AgentDefinitionResolver.class)
    public DispatchingBackingExecutor.AgentDefinitionResolver agentDefinitionResolver() {
        return agentId -> null;
    }

    /**
     * Default agent-backing handler: wraps the {@link AgentInvoker} bean when present.
     *
     * @param invoker   agent invoker
     * @param resolver  resolves agent definitions by id
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.AgentBackingHandler.class)
    @ConditionalOnBean(AgentInvoker.class)
    public DispatchingBackingExecutor.AgentBackingHandler agentBackingHandler(
            AgentInvoker invoker,
            DispatchingBackingExecutor.AgentDefinitionResolver resolver) {
        return DispatchingBackingExecutor.defaultAgentBackingHandler(invoker, resolver);
    }

    /**
     * The dispatching backing executor: routes to query, agent, or operation handlers.
     *
     * @param queryHandler     handles query-backed endpoints
     * @param agentHandler     handles agent-backed endpoints
     * @param operationHandler handles operation-backed endpoints
     * @return the executor
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.class)
    @ConditionalOnBean({DispatchingBackingExecutor.QueryBackingHandler.class,
                        DispatchingBackingExecutor.AgentBackingHandler.class,
                        DispatchingBackingExecutor.OperationBackingHandler.class})
    public DispatchingBackingExecutor dispatchingBackingExecutor(
            DispatchingBackingExecutor.QueryBackingHandler queryHandler,
            DispatchingBackingExecutor.AgentBackingHandler agentHandler,
            DispatchingBackingExecutor.OperationBackingHandler operationHandler) {
        return new DispatchingBackingExecutor(queryHandler, agentHandler, operationHandler);
    }

    /**
     * Default agent resolver: returns {@code null} for all slugs (no published agents without
     * the persistence module).
     *
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(AgentChatController.AgentResolver.class)
    public AgentChatController.AgentResolver agentResolver() {
        return slug -> null;
    }

    /**
     * The SSE streaming + sync chat controller for agent endpoints.
     * Registered only when the core pre-requisites ({@link AuthorizationEngine},
     * {@link GenericDynamicHandler.DaiPrincipalResolver}) are available.
     *
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(AgentChatController.class)
    @ConditionalOnBean({AuthorizationEngine.class, GenericDynamicHandler.DaiPrincipalResolver.class,
                        AgentInvoker.class})
    public AgentChatController agentChatController(
            AgentChatController.AgentResolver agentResolver,
            AgentInvoker agentInvoker,
            GenericDynamicHandler.DaiPrincipalResolver principalResolver,
            AuthorizationEngine authorizationEngine,
            GenericDynamicHandler.RateLimiter rateLimiter,
            GenericDynamicHandler.KillSwitchChecker killSwitchChecker) {
        return new AgentChatController(agentResolver, agentInvoker, principalResolver,
                authorizationEngine, rateLimiter, killSwitchChecker);
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
