package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.runtime.ConversationRecorder;
import com.springaimcpservercommon.ai.runtime.DefaultAgentInvoker;
import com.springaimcpservercommon.ai.runtime.TurnRecorder;
import com.springaimcpservercommon.ai.tool.AgentCatalogPort;
import com.springaimcpservercommon.ai.tool.ProposalService;
import com.springaimcpservercommon.ai.tool.SecuredToolCallback;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.ai.tool.ToolCallRecorder;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.ai.tool.WriteMode;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the Spring AI agent runtime integration.
 *
 * <p>Activated when {@link ToolBridge} is on the classpath (ai module present).
 *
 * <p>Per-turn objects ({@link InvocationGuardAdvisor}, {@link UsageMeteringAdvisor}) are
 * instantiated by the agent runtime per invocation, not registered as singleton beans.
 * This class registers the singleton port implementations and no-op defaults used by those advisors.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiSecurityAutoConfiguration.class,
                             DaiPersistenceAutoConfiguration.class})
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(ToolBridge.class)
@NullMarked
public class DaiAiAutoConfiguration {

    /**
     * Default no-op usage sink. Superseded by the ledger-backed sink from {@link DaiPersistenceAutoConfiguration}
     * when the {@code dynamic_ai} store is present.
     *
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(UsageMeteringAdvisor.UsageSink.class)
    public UsageMeteringAdvisor.UsageSink usageSink() {
        return (agent, principal, promptTokens, completionTokens) -> {};
    }

    /**
     * Default no-op budget checker — all turns are permitted by default.
     * Superseded by the ledger-backed checker from {@link DaiPersistenceAutoConfiguration} when the
     * {@code dynamic_ai} store is present and {@code dynamic.ai.agent.budget.enforce} is not {@code false}.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(InvocationGuardAdvisor.BudgetChecker.class)
    public InvocationGuardAdvisor.BudgetChecker budgetChecker() {
        return (agent, principal) -> true;
    }

    /**
     * Default no-op turn recorder. Superseded by the store-backed recorder from
     * {@link DaiPersistenceAutoConfiguration} when the {@code dynamic_ai} store is present.
     *
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(TurnRecorder.class)
    public TurnRecorder turnRecorder() {
        return TurnRecorder.NOOP;
    }

    /**
     * Default no-op tool-call recorder. Superseded by the store-backed recorder from
     * {@link DaiPersistenceAutoConfiguration} when the {@code dynamic_ai} store is present.
     *
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallRecorder.class)
    public ToolCallRecorder toolCallRecorder() {
        return ToolCallRecorder.NOOP;
    }

    /**
     * Default no-op conversation recorder. Superseded by the store-backed recorder from
     * {@link DaiPersistenceAutoConfiguration} when {@code dynamic.ai.agent.conversations.enabled=true}.
     *
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(ConversationRecorder.class)
    public ConversationRecorder conversationRecorder() {
        return ConversationRecorder.NOOP;
    }

    /**
     * Default {@link ModelRouter}: the host's {@code ChatModel} bean of the agent's provider. The models are
     * looked up when the router is created, not through a bean condition, because Spring AI's provider
     * auto-configurations sort after this one; a host without any {@code ChatModel} gets a router that answers
     * every turn with {@code model-unavailable}, which is clearer than an agent endpoint that does not exist.
     *
     * @param beans the bean factory, to find every {@code ChatModel} by name
     * @param props framework properties (provider breaker)
     * @return the router
     */
    @Bean
    @ConditionalOnMissingBean(ModelRouter.class)
    public ModelRouter modelRouter(org.springframework.beans.factory.ListableBeanFactory beans, DaiProperties props) {
        DaiProperties.Model model = props.model();
        return new DefaultModelRouter(beans.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class),
                model.failureThreshold(), model.breakerOpenFor(), java.time.Clock.systemUTC());
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
     * Per-call tool permission check (LLD-07 §3): the caller must hold {@code tool:invoke} (or {@code agent:invoke}
     * for an agent-backed tool) on the binding and, for a PROPOSE tool, also {@code data:write-propose}. Grants may
     * change mid-conversation, so this runs on every call. Default deny: without an
     * {@link AuthorizationEngine} nothing is permitted.
     *
     * @param engines the authorization engine, when the security layer is present
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(SecuredToolCallback.ToolPermissionChecker.class)
    public SecuredToolCallback.ToolPermissionChecker toolPermissionChecker(ObjectProvider<AuthorizationEngine> engines) {
        return (principal, binding) -> {
            AuthorizationEngine engine = engines.getIfAvailable();
            if (engine == null) {
                return false;
            }
            ResourceRef resource = ResourceRef.of(binding.workspaceId(), binding.id(), Classification.PUBLIC);
            List<Permission> required = new ArrayList<>();
            required.add(binding.source() instanceof ToolSource.AgentSource ? Permission.AGENT_INVOKE
                    : Permission.TOOL_INVOKE);
            if (binding.writeMode() == WriteMode.PROPOSE) {
                required.add(Permission.DATA_WRITE_PROPOSE);
            }
            for (Permission permission : required) {
                AuthorizationRequest request = AuthorizationRequest.onResource(principal, permission, resource)
                        .withToolName(binding.toolName());
                if (!(engine.decide(request) instanceof AuthorizationOutcome.Permit)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Default proposal service: refuses. A proposal that was never stored must not be reported to the model or the
     * user as created, so without the store-backed service (registered by {@link DaiPersistenceAutoConfiguration})
     * a PROPOSE tool answers {@code proposal_unavailable}.
     *
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(ProposalService.class)
    public ProposalService proposalService() {
        return request -> {
            throw new ProposalService.ProposalRefusedException("proposal_unavailable",
                    "Changes cannot be proposed here: no proposal store is configured.");
        };
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
     * @param toolCallRecorder     receives one record per tool call
     * @param observationRegistry  the host's registry for the {@code dai.tool} spans, when it has one
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
                                  ProposalService proposalService,
                                  ToolCallRecorder toolCallRecorder,
                                  ObjectProvider<ObservationRegistry> observationRegistry) {
        return new ToolBridge(bindingLoader, operationFactory, queryFactory,
                agentFactoryProvider.getIfAvailable(),
                permissionChecker, proposalService, toolCallRecorder, java.time.Clock.systemUTC(),
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * Chat memory the model reads. With a {@link ChatMemoryStore} (a persistence unit exists) and
     * {@code dynamic.ai.agent.memory.persistent=true} (default) the window lives in PostgreSQL, so every replica
     * shares it and it survives restarts (OQ-45, ADR-0021); otherwise it is this node's heap. The repository is
     * wrapped here and deliberately not exposed as a {@code ChatMemoryRepository} bean, so it cannot collide with
     * the host's own Spring AI memory configuration.
     *
     * @param storeProvider optional PostgreSQL memory store
     * @param props         framework properties
     * @return the memory
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(ObjectProvider<ChatMemoryStore> storeProvider, DaiProperties props) {
        ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        ChatMemoryStore store = storeProvider.getIfAvailable();
        DaiProperties.Memory memory = props.memory();
        if (store != null && memory.persistent()) {
            repository = new StoreChatMemoryRepository(store,
                    new MessageRedactor(new com.springaimcpservercommon.core.lint.SecretScanner(),
                            memory.maxStoredChars()),
                    memory.retention());
        }
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
    }

    /**
     * JSON Schema conformance validator backed by {@code com.networknt:json-schema-validator}
     * when that library is present on the classpath (draft-07). Active only when networknt is
     * available and no other {@link JsonSchemaValidationPort} bean has been registered.
     *
     * <p>Declared as a static nested {@link Configuration} so that the networknt import inside
     * {@link NetworkntJsonSchemaValidationPort} is only loaded when the condition passes.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.networknt.schema.SchemaRegistry")
    static class NetworkntSchemaConfiguration {

        /**
         * @return the networknt-backed validator
         */
        @Bean
        @ConditionalOnMissingBean(JsonSchemaValidationPort.class)
        public JsonSchemaValidationPort networkntJsonSchemaValidator() {
            return new NetworkntJsonSchemaValidationPort();
        }
    }

    /**
     * No-op fallback — always returns an empty error list so only Level 1 (well-formedness) is
     * enforced when networknt is not on the classpath and the host has not registered a custom
     * {@link JsonSchemaValidationPort}.
     *
     * @return the no-op port
     */
    @Bean
    @ConditionalOnMissingBean(JsonSchemaValidationPort.class)
    public JsonSchemaValidationPort noOpJsonSchemaValidator() {
        return (schema, json) -> java.util.List.of();
    }

    /**
     * Default {@link AgentInvoker}: assembles a {@code ChatClient} per turn and dispatches
     * sync and streaming calls (LLD-06 §3).
     *
     * <p>Requires a {@link ModelRouter} and {@link MetadataRegistry} bean; {@link ToolBridge}
     * is optional (injected via {@link ObjectProvider}, tools disabled when absent).
     * {@link JsonSchemaValidationPort} is optional — when absent, only well-formedness is enforced.
     *
     * @param modelRouter              resolves the ChatModel for each turn
     * @param toolBridgeProvider       optional ToolBridge (absent when no binding loaders are registered)
     * @param metadataRegistry         current effective catalog snapshot
     * @param killSwitchChecker        runtime kill-switch check
     * @param budgetChecker            token-budget pre-check
     * @param usageSink                token usage accounting
     * @param turnRecorder             per-turn telemetry (trace viewer)
     * @param conversationRecorder     conversation history (F-44)
     * @param observationRegistry      Micrometer registry
     * @param chatMemory               conversation history store
     * @param schemaValidatorProvider  optional JSON Schema conformance validator
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
            TurnRecorder turnRecorder,
            ConversationRecorder conversationRecorder,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ChatMemory chatMemory,
            ObjectProvider<JsonSchemaValidationPort> schemaValidatorProvider) {
        return new DefaultAgentInvoker(
                modelRouter,
                toolBridgeProvider.getIfAvailable(),
                metadataRegistry,
                killSwitchChecker,
                budgetChecker,
                usageSink,
                turnRecorder,
                conversationRecorder,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                chatMemory,
                schemaValidatorProvider.getIfAvailable());
    }
}
