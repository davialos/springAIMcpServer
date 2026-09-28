package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.runtime.DefaultAgentInvoker;
import com.springaimcpservercommon.ai.tool.AgentCatalogPort;
import com.springaimcpservercommon.ai.tool.ProposalService;
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
     * Default no-op proposal service. Replaced by the persistence module when write-proposal
     * persistence is enabled. Returns a synthetic UUID without persisting.
     *
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(ProposalService.class)
    public ProposalService proposalService() {
        return (toolName, toolInput, bindingId, principal) -> com.springaimcpservercommon.core.id.Ids.newId();
    }

    /**
     * Sub-agent callback factory. Resolves lazily through {@link ObjectProvider} to break the
     * circular dependency between {@link ToolBridge} and {@link AgentInvoker}:
     * <ul>
     *   <li>{@code ToolBridge} depends on {@code AgentCallbackFactory}</li>
     *   <li>{@code AgentCallbackFactory} holds an {@code ObjectProvider<AgentInvoker>} (lazy)</li>
     *   <li>{@code DefaultAgentInvoker} depends on {@code ToolBridge} (via {@code ObjectProvider})</li>
     * </ul>
     * The {@code ObjectProvider} is only resolved at request time (inside the factory lambda),
     * so the construction order remains acyclic.
     *
     * <p>Active only when an {@link AgentCatalogPort} bean is present (registered by the persistence
     * module). Without it sub-agent delegation logs a warning and returns {@code null}.
     *
     * @param agentCatalogProvider  optional port for loading agent definitions by id
     * @param agentInvokerProvider  lazy reference to the agent invoker (breaks the cycle)
     * @return the factory
     */
    /**
     * Sub-agent callback factory — active only when an {@link AgentCatalogPort} bean is present.
     * Uses {@code ObjectProvider<AgentInvoker>} to avoid the circular bean dependency:
     * {@code ToolBridge → AgentCallbackFactory → ObjectProvider<AgentInvoker>} (lazy).
     *
     * @param agentCatalogProvider optional port for loading agent definitions
     * @param agentInvokerProvider lazy reference to the AgentInvoker
     * @return the factory, or a no-op factory when no AgentCatalogPort is registered
     */
    @Bean
    @ConditionalOnMissingBean(ToolBridge.AgentCallbackFactory.class)
    public ToolBridge.AgentCallbackFactory agentCallbackFactory(
            ObjectProvider<AgentCatalogPort> agentCatalogProvider,
            ObjectProvider<AgentInvoker> agentInvokerProvider) {
        AgentCatalogPort catalog = agentCatalogProvider.getIfAvailable();
        if (catalog == null) {
            // No agent catalog: AgentSource tools will log a warn and return null
            return (agentId, binding, principal, auth) -> null;
        }
        return ToolBridge.defaultAgentCallbackFactory(agentInvokerProvider::getIfAvailable, catalog);
    }

    /**
     * The tool bridge singleton. Assembled when all required port beans are available:
     * {@link ToolBridge.ToolBindingLoader}, {@link ToolBridge.OperationCallbackFactory},
     * {@link ToolBridge.QueryCallbackFactory}.
     *
     * <p>The persistence module registers the binding loader; the operation and query
     * callback factories are provided by the host or by persistence-aware adapters.
     * {@link ToolBridge.AgentCallbackFactory} is optional — sub-agent routing is disabled when absent.
     *
     * @param bindingLoader        loads tool bindings
     * @param operationFactory     builds operation-backed callbacks
     * @param queryFactory         builds query-backed callbacks
     * @param agentFactoryProvider optional sub-agent callback factory
     * @param permissionChecker    runtime per-call permission check
     * @param proposalService      creates ChangeProposal records for PROPOSE-mode tools
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
                                  ObjectProvider<ToolBridge.AgentCallbackFactory> agentFactoryProvider,
                                  SecuredToolCallback.ToolPermissionChecker permissionChecker,
                                  ProposalService proposalService) {
        return new ToolBridge(bindingLoader, operationFactory, queryFactory,
                agentFactoryProvider.getIfAvailable(),
                permissionChecker, proposalService);
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
