package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.runtime.DefaultAgentInvoker;
import com.springaimcpservercommon.ai.tool.SecuredToolCallback;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring AI agent runtime integration.
 *
 * <p>Activated when {@link ToolBridge} is on the classpath (ai module present).
 *
 * <p>Per-turn objects ({@link InvocationGuardAdvisor}, {@link UsageMeteringAdvisor}) are
 * instantiated by the agent runtime per invocation, not registered as singleton beans.
 * This class registers the singleton port implementations and no-op defaults used by those advisors.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiSecurityAutoConfiguration.class})
@ConditionalOnClass(ToolBridge.class)
@NullMarked
public class DaiAiAutoConfiguration {

    /**
     * Default no-op usage sink. Replaced by the persistence module when budget tracking is enabled.
     *
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(UsageMeteringAdvisor.UsageSink.class)
    public UsageMeteringAdvisor.UsageSink usageSink() {
        return (principal, agentId, promptTokens, completionTokens) -> {};
    }

    /**
     * Default no-op budget checker — all turns are permitted by default.
     * Replace with a persistence-backed implementation for budget enforcement.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(InvocationGuardAdvisor.BudgetChecker.class)
    public InvocationGuardAdvisor.BudgetChecker budgetChecker() {
        return (principal, agentId) -> true;
    }

    /**
     * Default kill-switch checker — all agents are enabled by default.
     * The persistence module or the admin control-plane replaces this with a
     * database-backed kill-switch view.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(InvocationGuardAdvisor.KillSwitchChecker.class)
    public InvocationGuardAdvisor.KillSwitchChecker agentKillSwitchChecker() {
        return agentId -> true;
    }

    /**
     * Default tool permission checker — permits all invocations.
     * Replace with an {@link com.springaimcpservercommon.security.authz.AuthorizationEngine}-backed
     * implementation for per-call security enforcement.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(SecuredToolCallback.ToolPermissionChecker.class)
    public SecuredToolCallback.ToolPermissionChecker toolPermissionChecker() {
        return (principal, binding) -> true;
    }

    /**
     * The tool bridge singleton. Assembled when all required port beans are available:
     * {@link ToolBridge.ToolBindingLoader}, {@link ToolBridge.OperationCallbackFactory},
     * {@link ToolBridge.QueryCallbackFactory}.
     *
     * <p>The persistence module registers the binding loader; the operation and query
     * callback factories are provided by the host or by persistence-aware adapters.
     *
     * @param bindingLoader     loads tool bindings
     * @param operationFactory  builds operation-backed callbacks
     * @param queryFactory      builds query-backed callbacks
     * @param permissionChecker runtime per-call permission check
     * @return the bridge
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ToolBridge.ToolBindingLoader.class,
                        ToolBridge.OperationCallbackFactory.class,
                        ToolBridge.QueryCallbackFactory.class})
    public ToolBridge toolBridge(ToolBridge.ToolBindingLoader bindingLoader,
                                  ToolBridge.OperationCallbackFactory operationFactory,
                                  ToolBridge.QueryCallbackFactory queryFactory,
                                  SecuredToolCallback.ToolPermissionChecker permissionChecker) {
        return new ToolBridge(bindingLoader, operationFactory, queryFactory, permissionChecker);
    }

    /**
     * Observation registry fallback when Micrometer is not otherwise configured.
     *
     * @return a no-op registry
     */
    @Bean
    @ConditionalOnMissingBean(ObservationRegistry.class)
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.NOOP;
    }

    /**
     * Default in-memory chat memory store. Replace with a PostgreSQL-backed implementation
     * (via the persistence module) for multi-replica deployments (ADR-0021).
     *
     * @return the memory store
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }

    /**
     * Default {@link AgentInvoker}: assembles a {@code ChatClient} per turn and dispatches
     * sync and streaming calls (LLD-06 §3).
     *
     * <p>Requires a {@link ModelRouter} and {@link MetadataRegistry} bean; {@link ToolBridge}
     * is optional (injected via {@link ObjectProvider}, tools disabled when absent).
     *
     * @param modelRouter          resolves the ChatModel for each turn
     * @param toolBridgeProvider   optional ToolBridge (absent when no binding loaders are registered)
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param observationRegistry  Micrometer registry
     * @param chatMemory           conversation history store
     * @return the invoker
     */
    @Bean
    @ConditionalOnMissingBean(AgentInvoker.class)
    @ConditionalOnBean({ModelRouter.class, MetadataRegistry.class})
    public DefaultAgentInvoker defaultAgentInvoker(
            ModelRouter modelRouter,
            ObjectProvider<ToolBridge> toolBridgeProvider,
            MetadataRegistry metadataRegistry,
            InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
            InvocationGuardAdvisor.BudgetChecker budgetChecker,
            UsageMeteringAdvisor.UsageSink usageSink,
            ObservationRegistry observationRegistry,
            ChatMemory chatMemory) {
        return new DefaultAgentInvoker(
                modelRouter,
                toolBridgeProvider.getIfAvailable(),
                metadataRegistry,
                killSwitchChecker,
                budgetChecker,
                usageSink,
                observationRegistry,
                chatMemory);
    }
}
