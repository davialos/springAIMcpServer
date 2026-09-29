package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.webmvc.endpoint.AgentChatController;
import com.springaimcpservercommon.webmvc.endpoint.DispatchingBackingExecutor;
import com.springaimcpservercommon.webmvc.endpoint.DynamicEndpointRegistrar;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import com.springaimcpservercommon.webmvc.endpoint.InMemoryTurnEventBuffer;
import com.springaimcpservercommon.webmvc.endpoint.TurnEventBuffer;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for the dynamic REST endpoint engine (LLD-04).
 *
 * <p>Activated when {@link GenericDynamicHandler} is on the classpath (webmvc module present)
 * and Spring MVC's {@link RequestMappingHandlerMapping} is available.
 *
 * <p>All beans are {@link ConditionalOnMissingBean}; hosts may replace any component.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiPersistenceAutoConfiguration.class,
                             DaiSecurityAutoConfiguration.class, DaiAiAutoConfiguration.class,
                             WebMvcAutoConfiguration.class})
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
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
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new GenericDynamicHandler(registrar, principalResolver, authorizationEngine,
                backingExecutor, killSwitchChecker, rateLimiter,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
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
     * Real operation backing handler: invokes {@code @AiExposedAction}-annotated host methods
     * through their Spring proxy using metadata from the {@link MetadataRegistry} (LLD-06 §3).
     *
     * <p>Parameter binding uses {@link OperationDescriptor#params()} indexed by name from
     * {@code bindings}. The method is resolved via {@link OperationDescriptor#invocationType()}
     * (the interface type, so JDK proxies work), and the Spring bean is looked up by
     * {@link OperationDescriptor#beanName()}. Result is serialized via {@link CanonicalJson#write}.
     *
     * <p>Spring Security access control (method-level {@code @PreAuthorize} etc.) fires naturally
     * because the call goes through the proxy.
     *
     * @param metadataRegistry  live effective catalog
     * @param applicationContext Spring context for proxy-bean lookup
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.OperationBackingHandler.class)
    @ConditionalOnBean(MetadataRegistry.class)
    public DispatchingBackingExecutor.OperationBackingHandler operationBackingHandler(
            MetadataRegistry metadataRegistry,
            ApplicationContext applicationContext) {
        return (operationRef, bindings, principal) ->
                invokeOperation(metadataRegistry, applicationContext, operationRef, bindings);
    }

    private static String invokeOperation(
            MetadataRegistry metadataRegistry,
            ApplicationContext applicationContext,
            com.springaimcpservercommon.core.catalog.CatalogElementRef operationRef,
            Map<String, Object> bindings) throws GenericDynamicHandler.BackingException {
        EffectiveOperation op = metadataRegistry.current().operation(operationRef).orElse(null);
        if (op == null || !op.enabled()) {
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.RESOURCE_SUSPENDED,
                    "Operation " + operationRef + " is not available.");
        }
        OperationDescriptor desc = op.descriptor();
        Object bean;
        try {
            bean = applicationContext.getBean(desc.beanName());
        } catch (org.springframework.beans.BeansException e) {
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.EXECUTION_ERROR,
                    "Bean '" + desc.beanName() + "' not found in application context.");
        }
        Class<?> invocationType;
        try {
            invocationType = resolveClass(desc.invocationType());
        } catch (ClassNotFoundException e) {
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.EXECUTION_ERROR,
                    "Invocation type not found: " + desc.invocationType());
        }
        List<String> paramTypeNames = desc.parameterTypes();
        Class<?>[] paramTypes = new Class<?>[paramTypeNames.size()];
        for (int i = 0; i < paramTypeNames.size(); i++) {
            try {
                paramTypes[i] = resolveClass(paramTypeNames.get(i));
            } catch (ClassNotFoundException e) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.EXECUTION_ERROR,
                        "Parameter type not found: " + paramTypeNames.get(i));
            }
        }
        Method method;
        try {
            method = invocationType.getMethod(desc.methodName(), paramTypes);
        } catch (NoSuchMethodException e) {
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.EXECUTION_ERROR,
                    "Method '" + desc.methodName() + "' not found on " + desc.invocationType());
        }
        List<ParamDescriptor> params = desc.params();
        Object[] args = new Object[params.size()];
        for (ParamDescriptor pd : params) {
            args[pd.index()] = bindings.get(pd.name());
        }
        try {
            Object result = method.invoke(bean, args);
            return result == null ? "null" : CanonicalJson.write(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AccessDeniedException) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.ACCESS_DENIED,
                        cause.getMessage() != null ? cause.getMessage() : "Access denied.");
            }
            String msg = cause != null ? cause.getMessage() : null;
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.EXECUTION_ERROR,
                    msg != null ? msg : "Operation invocation failed.");
        } catch (IllegalAccessException e) {
            throw new GenericDynamicHandler.BackingException(
                    ProblemCode.EXECUTION_ERROR, "Method is not accessible.");
        }
    }

    private static Class<?> resolveClass(String typeName) throws ClassNotFoundException {
        return switch (typeName) {
            case "int"     -> int.class;
            case "long"    -> long.class;
            case "boolean" -> boolean.class;
            case "double"  -> double.class;
            case "float"   -> float.class;
            case "short"   -> short.class;
            case "byte"    -> byte.class;
            case "char"    -> char.class;
            case "void"    -> void.class;
            default        -> Class.forName(typeName);
        };
    }

    /**
     * Default operation backing handler: placeholder that returns an empty JSON object.
     * Active only when {@link MetadataRegistry} is absent (no core module).
     *
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.OperationBackingHandler.class)
    public DispatchingBackingExecutor.OperationBackingHandler operationBackingHandlerNoOp() {
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
     * Default in-memory turn event buffer enabling SSE stream replay (LLD-13 §5).
     *
     * <p>Stores up to 256 events per turn and retains completed turns for 5 minutes.
     * Replace with a PostgreSQL-backed bean for cross-replica replay (ADR-0021).
     *
     * @return the buffer
     */
    @Bean
    @ConditionalOnMissingBean(TurnEventBuffer.class)
    public TurnEventBuffer turnEventBuffer() {
        return new InMemoryTurnEventBuffer();
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
     * <p>{@link TurnEventBuffer} is optional — when absent the replay endpoint returns 503.
     *
     * @param turnEventBufferProvider optional ring buffer for SSE stream replay
     * @param budgetCheckerProvider   optional budget check for an early {@code 429 budget-exhausted}
     * @param props                   framework properties (chat limits)
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
            GenericDynamicHandler.KillSwitchChecker killSwitchChecker,
            ObjectProvider<TurnEventBuffer> turnEventBufferProvider,
            ObjectProvider<InvocationGuardAdvisor.BudgetChecker> budgetCheckerProvider,
            DaiProperties props) {
        DaiProperties.Chat chat = props.chat();
        return new AgentChatController(agentResolver, agentInvoker, principalResolver,
                authorizationEngine, rateLimiter, killSwitchChecker,
                turnEventBufferProvider.getIfAvailable(), budgetCheckerProvider.getIfAvailable(),
                new AgentChatController.Settings(chat.streamIdleTimeout(), chat.maxMessageChars()));
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
