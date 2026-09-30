package com.springaimcpservercommon.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.runtime.ConversationRecorder;
import com.springaimcpservercommon.ai.runtime.TurnRecorder;
import com.springaimcpservercommon.ai.tool.ProposalService;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.ai.tool.ToolCallRecorder;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.agent.ToolBindingRef;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.GrantStore;
import com.springaimcpservercommon.persistence.config.KillSwitchStore;
import com.springaimcpservercommon.persistence.identity.ApiKeyStore;
import com.springaimcpservercommon.persistence.identity.RoleMappingStore;
import com.springaimcpservercommon.persistence.identity.McpClientStore;
import com.springaimcpservercommon.persistence.identity.PrincipalDirectory;
import com.springaimcpservercommon.security.port.ApiKeyLookup;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.McpClientRegistryPort;
import com.springaimcpservercommon.security.port.MembershipSource;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;
import com.springaimcpservercommon.security.port.ResourceStatusView;
import com.springaimcpservercommon.security.port.RoleMappingSource;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.usage.BudgetStore;
import com.springaimcpservercommon.persistence.usage.PriceStore;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.PublishedSnapshot;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceSettings;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.springaimcpservercommon.query.ast.AttributePath;
import com.springaimcpservercommon.query.ast.FilterNode;
import com.springaimcpservercommon.query.ast.Operand;
import com.springaimcpservercommon.query.ast.Operator;
import com.springaimcpservercommon.query.ast.PageSpec;
import com.springaimcpservercommon.query.ast.Projection;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.QueryParam;
import com.springaimcpservercommon.query.ast.SortSpec;
import com.springaimcpservercommon.query.execution.QueryBulkheadException;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import com.springaimcpservercommon.webmvc.endpoint.AgentChatController;
import com.springaimcpservercommon.webmvc.endpoint.DispatchingBackingExecutor;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the framework's isolated persistence unit (ADR-0019).
 *
 * <p>Activated when {@link ConfigStore} is on the classpath (persistence module present) and a
 * {@link DataSource} bean is available from the host. Creates the {@link DaiPersistenceUnit}
 * (Flyway + Hibernate EMF, never registered as beans), a {@link ConfigStore} bean, and persistence-backed
 * implementations of {@link AgentChatController.AgentResolver} and
 * {@link DispatchingBackingExecutor.AgentDefinitionResolver}.
 *
 * <p>These resolver beans supersede the no-op defaults registered by {@link DaiWebMvcAutoConfiguration}
 * (via {@code @ConditionalOnMissingBean}), because this configuration runs before it.
 */
// After Boot's DataSource auto-configuration: the persistence unit needs the host's DataSource bean, and
// @ConditionalOnBean only sees beans defined by earlier configurations (by name: spring-boot-jdbc is optional).
@AutoConfiguration(after = DaiCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass({ConfigStore.class, DaiPersistenceUnit.class})
@NullMarked
public class DaiPersistenceAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DaiPersistenceAutoConfiguration.class);

    /**
     * Private Jackson 3 mapper used only to parse agent spec JSON.
     * Never registered as a Spring bean (ADR-0019).
     */
    private static final JsonMapper SPEC_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * The framework's isolated persistence unit: Flyway migrations + Hibernate EMF + transaction managers.
     * Neither the {@link jakarta.persistence.EntityManagerFactory} nor the transaction manager is registered
     * as a Spring bean (ADR-0019). Spring lifecycle calls {@link DaiPersistenceUnit#close()} on context shutdown.
     *
     * @param dataSource host-provided data source
     * @param props      framework properties for environment identification
     * @return the started persistence unit
     */
    @Bean
    @ConditionalOnMissingBean(DaiStore.class)
    @ConditionalOnBean(DataSource.class)
    public DaiPersistenceUnit daiPersistenceUnit(DataSource dataSource, DaiProperties props) {
        DaiProperties.Environment env = props.environment();
        String tier = resolveTier(env.tier());
        String envId = environmentId(env, tier);
        DaiPersistenceSettings defaults = DaiPersistenceSettings.defaults(envId, tier);
        DaiProperties.Store store = props.store();
        DaiPersistenceSettings settings = new DaiPersistenceSettings(defaults.schema(), store.migrate(), null, envId,
                tier, store.validateSchema(), defaults.jdbcBatchSize());
        LOG.info("Starting dynamic_ai persistence unit (schema {}, env {}/{}, migrate {}, validate {})",
                settings.schema(), envId, tier, settings.migrate(), settings.validateSchema());
        return DaiPersistenceUnit.start(dataSource, settings);
    }

    /**
     * The configuration store: resource lifecycle, snapshot generations, drift detection (LLD-09).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ConfigStore.class)
    @ConditionalOnBean(DaiStore.class)
    public ConfigStore configStore(DaiStore store) {
        return new ConfigStore(store);
    }

    /**
     * The tamper-evident audit trail (F-66). Needs the same environment id the persistence unit was started
     * with, so events are chained per environment.
     *
     * @param store the framework's persistence unit
     * @param props framework properties for environment identification
     * @return the audit trail
     */
    @Bean
    @ConditionalOnMissingBean(AuditTrail.class)
    @ConditionalOnBean(DaiStore.class)
    public AuditTrail auditTrail(DaiStore store, DaiProperties props) {
        DaiProperties.Environment env = props.environment();
        return new AuditTrail(store, environmentId(env, resolveTier(env.tier())), java.time.Clock.systemUTC());
    }

    /**
     * Kill switch store (F-73).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(KillSwitchStore.class)
    @ConditionalOnBean(DaiStore.class)
    public KillSwitchStore killSwitchStore(DaiStore store) {
        return new KillSwitchStore(store);
    }

    /**
     * Workspace and membership store (F-62).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(WorkspaceStore.class)
    @ConditionalOnBean(DaiStore.class)
    public WorkspaceStore workspaceStore(DaiStore store) {
        return new WorkspaceStore(store);
    }

    /**
     * IdP group/claim to role mapping store (F-61).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(RoleMappingStore.class)
    @ConditionalOnBean(DaiStore.class)
    public RoleMappingStore roleMappingStore(DaiStore store) {
        return new RoleMappingStore(store);
    }

    /**
     * Fine-grained grant store (F-63).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(GrantStore.class)
    @ConditionalOnBean(DaiStore.class)
    public GrantStore grantStore(DaiStore store) {
        return new GrantStore(store);
    }

    /**
     * Service account and API key store (F-65).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyStore.class)
    @ConditionalOnBean(DaiStore.class)
    public ApiKeyStore apiKeyStore(DaiStore store) {
        return new ApiKeyStore(store);
    }

    /**
     * Change proposal store (F-45, LLD-11).
     *
     * @param store the framework's persistence unit
     * @param props framework properties (terminal-state retention)
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ChangeProposalStore.class)
    @ConditionalOnBean(DaiStore.class)
    public ChangeProposalStore changeProposalStore(DaiStore store, DaiProperties props) {
        return new ChangeProposalStore(store, java.time.Clock.systemUTC(), props.write().retention());
    }

    /**
     * Store-backed proposal creation for tools in PROPOSE mode (F-45, LLD-11): records the reviewable proposal, never
     * writes to the host. Off until {@code dynamic.ai.agent.write.enabled=true} (the tools then answer
     * {@code writes_disabled}). Supersedes the refusing default of {@link DaiAiAutoConfiguration}.
     *
     * @param store    proposal store
     * @param registry live catalog (resolves the operation and its record type)
     * @param props    framework properties
     * @param versions the host record versions (base version and before-values), when the host has JPA entities
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(ProposalService.class)
    @ConditionalOnBean({ChangeProposalStore.class, MetadataRegistry.class})
    public ProposalService storeProposalService(ChangeProposalStore store, MetadataRegistry registry,
                                                DaiProperties props,
                                                org.springframework.beans.factory.ObjectProvider<
                                                        com.springaimcpservercommon.core.versioning.RecordVersions>
                                                        versions) {
        return new StoreProposalService(store, registry::current, props.write(), versions::getIfAvailable);
    }

    /**
     * Token/cost budget store (F-70).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(BudgetStore.class)
    @ConditionalOnBean(DaiStore.class)
    public BudgetStore budgetStore(DaiStore store) {
        return new BudgetStore(store);
    }

    /**
     * Hourly usage ledger (F-70, F-71).
     *
     * @param store the framework's persistence unit
     * @return the ledger
     */
    @Bean
    @ConditionalOnMissingBean(UsageLedger.class)
    @ConditionalOnBean(DaiStore.class)
    public UsageLedger usageLedger(DaiStore store) {
        return new UsageLedger(store);
    }

    /**
     * Model price history (F-71).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(PriceStore.class)
    @ConditionalOnBean(DaiStore.class)
    public PriceStore priceStore(DaiStore store) {
        return new PriceStore(store);
    }

    /**
     * Telemetry store: conversations, turns, model calls, tool invocations (F-44, F-72).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(TelemetryStore.class)
    @ConditionalOnBean(DaiStore.class)
    public TelemetryStore telemetryStore(DaiStore store) {
        return new TelemetryStore(store, java.time.Clock.systemUTC());
    }

    /**
     * PostgreSQL store for the model's chat memory (OQ-45).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryStore.class)
    @ConditionalOnBean(DaiStore.class)
    public ChatMemoryStore chatMemoryStore(DaiStore store) {
        return new ChatMemoryStore(store, java.time.Clock.systemUTC());
    }

    /**
     * Ledger-backed budget check for the invocation path (F-70). Supersedes the permit-all default of
     * {@link DaiAiAutoConfiguration}; disable enforcement with {@code dynamic.ai.agent.budget.enforce=false}.
     *
     * @param budgets       budget store
     * @param ledger        usage ledger
     * @param auditTrailProvider optional audit trail for soft-limit and exceeded alerts
     * @param props         framework properties
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(InvocationGuardAdvisor.BudgetChecker.class)
    @ConditionalOnBean({BudgetStore.class, UsageLedger.class})
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "dynamic.ai.agent.budget", name = "enforce", havingValue = "true", matchIfMissing = true)
    public InvocationGuardAdvisor.BudgetChecker ledgerBudgetChecker(
            BudgetStore budgets, UsageLedger ledger,
            org.springframework.beans.factory.ObjectProvider<AuditTrail> auditTrailProvider, DaiProperties props) {
        DaiProperties.Budget budget = props.budget();
        return new LedgerBudgetChecker(budgets, ledger, auditTrailProvider.getIfAvailable(), budget.cacheTtl(),
                budget.failOpen(), java.time.Clock.systemUTC());
    }

    /**
     * Prices model usage from the price history; shared by usage and turn recording.
     *
     * @param prices model price history
     * @return the calculator
     */
    @Bean
    @ConditionalOnMissingBean(ModelCostCalculator.class)
    @ConditionalOnBean(PriceStore.class)
    ModelCostCalculator modelCostCalculator(PriceStore prices) {
        return new ModelCostCalculator(prices, Duration.ofSeconds(60));
    }

    /**
     * Ledger-backed usage recording: tokens and priced cost of every completed model call (F-70, F-71).
     * Supersedes the no-op default of {@link DaiAiAutoConfiguration}.
     *
     * @param ledger usage ledger
     * @param costs  cost calculator
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(UsageMeteringAdvisor.UsageSink.class)
    @ConditionalOnBean({UsageLedger.class, ModelCostCalculator.class})
    public UsageMeteringAdvisor.UsageSink ledgerUsageSink(UsageLedger ledger, ModelCostCalculator costs) {
        return new LedgerUsageSink(ledger, costs, java.time.Clock.systemUTC());
    }

    /**
     * Store-backed turn recording for the trace viewer (F-72): one turn row and one priced model call row per
     * finished agent turn, written off the request path. Supersedes the no-op default of
     * {@link DaiAiAutoConfiguration}.
     *
     * @param store telemetry store
     * @param costs cost calculator
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(TurnRecorder.class)
    @ConditionalOnBean({TelemetryStore.class, ModelCostCalculator.class})
    public TurnRecorder storeTurnRecorder(TelemetryStore store, ModelCostCalculator costs) {
        return new StoreTurnRecorder(store, costs);
    }

    /**
     * Store-backed tool-call recording (F-72): one {@code dai_tool_invocation} row per tool call, written off the
     * request path. Supersedes the no-op default of {@link DaiAiAutoConfiguration}.
     *
     * @param store telemetry store
     * @return the recorder
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ToolCallRecorder.class)
    @ConditionalOnBean(TelemetryStore.class)
    public ToolCallRecorder storeToolCallRecorder(TelemetryStore store) {
        return new StoreToolCallRecorder(store);
    }

    /**
     * Store-backed conversation history (F-44): the redacted user message and answer of every successful turn.
     * Off unless {@code dynamic.ai.agent.conversations.enabled=true}. Supersedes the no-op default of
     * {@link DaiAiAutoConfiguration}.
     *
     * @param store telemetry store
     * @param props framework properties (retention, stored size)
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(ConversationRecorder.class)
    @ConditionalOnBean(TelemetryStore.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "dynamic.ai.agent.conversations", name = "enabled", havingValue = "true")
    public ConversationRecorder storeConversationRecorder(TelemetryStore store, DaiProperties props) {
        DaiProperties.Conversations c = props.conversations();
        return new StoreConversationRecorder(store,
                new MessageRedactor(new com.springaimcpservercommon.core.lint.SecretScanner(), c.maxStoredChars()),
                c.retention());
    }

    /**
     * Deletes conversations past their retention, whether or not recording is currently enabled.
     *
     * @param store telemetry store
     * @param props framework properties (purge interval)
     * @return the job
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConversationRetentionJob.class)
    @ConditionalOnBean(TelemetryStore.class)
    ConversationRetentionJob conversationRetentionJob(TelemetryStore store,
            org.springframework.beans.factory.ObjectProvider<ChatMemoryStore> memoryStore, DaiProperties props) {
        return new ConversationRetentionJob(store, memoryStore.getIfAvailable(), props.conversations().purgeInterval());
    }

    // ─── Security ports over the store (see StoreSecurityPorts) ────────────────
    // Without these no AuthorizationEngine, AuthorityMapper or principal resolver exists, and the admin API, agent
    // chat and dynamic endpoints are never registered.

    /**
     * Principal directory store ({@code dai_principal}).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalDirectory.class)
    @ConditionalOnBean(DaiStore.class)
    public PrincipalDirectory principalDirectory(DaiStore store) {
        return new PrincipalDirectory(store);
    }

    /**
     * MCP client registry store ({@code dai_mcp_client}).
     *
     * @param store the framework's persistence unit
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(McpClientStore.class)
    @ConditionalOnBean(DaiStore.class)
    public McpClientStore mcpClientStore(DaiStore store) {
        return new McpClientStore(store);
    }

    /**
     * Principal directory port over the store; a disabled subject can never be mapped.
     *
     * @param directory principal directory store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalDirectoryPort.class)
    @ConditionalOnBean(PrincipalDirectory.class)
    public PrincipalDirectoryPort storePrincipalDirectoryPort(PrincipalDirectory directory) {
        return new StoreSecurityPorts.Directory(directory);
    }

    /**
     * Workspace membership port over the store.
     *
     * @param workspaces workspace store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(MembershipSource.class)
    @ConditionalOnBean(WorkspaceStore.class)
    public MembershipSource storeMembershipSource(WorkspaceStore workspaces) {
        return new StoreSecurityPorts.Memberships(workspaces);
    }

    /**
     * Role mapping port over the store (reloaded every 10 s).
     *
     * @param store role mapping store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(RoleMappingSource.class)
    @ConditionalOnBean(RoleMappingStore.class)
    public RoleMappingSource storeRoleMappingSource(RoleMappingStore store) {
        return new StoreSecurityPorts.RoleMappings(store, Duration.ofSeconds(10), java.time.Clock.systemUTC());
    }

    /**
     * Grant port over the store (cached 5 s).
     *
     * @param store grant store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(GrantSource.class)
    @ConditionalOnBean(GrantStore.class)
    public GrantSource storeGrantSource(GrantStore store) {
        return new StoreSecurityPorts.Grants(store, Duration.ofSeconds(5), java.time.Clock.systemUTC());
    }

    /**
     * Kill switch port over the store (reloaded every 2 s, so a switch is effective on every node within seconds).
     *
     * @param store kill switch store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(KillSwitchView.class)
    @ConditionalOnBean(KillSwitchStore.class)
    public KillSwitchView storeKillSwitchView(KillSwitchStore store) {
        return new StoreSecurityPorts.KillSwitches(store, Duration.ofSeconds(2), java.time.Clock.systemUTC());
    }

    /**
     * Resource publication status port over the config store (cached 5 s).
     *
     * @param configStore config store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(ResourceStatusView.class)
    @ConditionalOnBean(ConfigStore.class)
    public ResourceStatusView storeResourceStatusView(ConfigStore configStore) {
        return new StoreSecurityPorts.ResourceStatuses(configStore, Duration.ofSeconds(5),
                java.time.Clock.systemUTC());
    }

    /**
     * Lets the admin write path make a publish visible to authorization at once (same hook the snapshot caches
     * use). Without it a resource checked while still a draft reads as unpublished for up to the cache TTL after
     * it is published.
     *
     * @param status the status port
     * @return the refresh hook, or a no-op for a host-supplied port
     */
    @Bean
    @ConditionalOnBean(ResourceStatusView.class)
    SnapshotView resourceStatusRefresh(ResourceStatusView status) {
        return new SnapshotView() {
            @Override
            public void refreshNow() {
                if (status instanceof StoreSecurityPorts.ResourceStatuses statuses) {
                    statuses.invalidateAll();
                }
            }

            @Override
            public long loadedGeneration() {
                return 0;
            }
        };
    }

    /**
     * API key lookup port over the store.
     *
     * @param store API key store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyLookup.class)
    @ConditionalOnBean(ApiKeyStore.class)
    public ApiKeyLookup storeApiKeyLookup(ApiKeyStore store) {
        return new StoreSecurityPorts.ApiKeys(store);
    }

    /**
     * MCP client registry port over the store.
     *
     * @param store MCP client store
     * @return the port
     */
    @Bean
    @ConditionalOnMissingBean(McpClientRegistryPort.class)
    @ConditionalOnBean(McpClientStore.class)
    public McpClientRegistryPort storeMcpClientRegistry(McpClientStore store) {
        return new StoreSecurityPorts.McpClients(store);
    }

    /**
     * Makes kill switches effective for dynamic endpoints: an endpoint or agent with an active switch answers
     * {@code endpoint-disabled}. Supersedes the permit-all default of {@link DaiWebMvcAutoConfiguration}.
     *
     * @param killSwitches kill switch port
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(GenericDynamicHandler.KillSwitchChecker.class)
    @ConditionalOnBean(KillSwitchView.class)
    public GenericDynamicHandler.KillSwitchChecker storeWebKillSwitchChecker(KillSwitchView killSwitches) {
        return resourceId -> killSwitches.findActive(null, resourceId, null, java.time.Instant.now()).isPresent();
    }

    /**
     * Makes kill switches effective inside the agent runtime (a switched-off agent refuses the turn). Supersedes
     * the all-enabled default of {@link DaiAiAutoConfiguration}.
     *
     * @param killSwitches kill switch port
     * @return the checker
     */
    @Bean
    @ConditionalOnMissingBean(InvocationGuardAdvisor.KillSwitchChecker.class)
    @ConditionalOnBean(KillSwitchView.class)
    public InvocationGuardAdvisor.KillSwitchChecker storeAgentKillSwitchChecker(KillSwitchView killSwitches) {
        return agentId -> killSwitches.findActive(null, agentId, null, java.time.Instant.now()).isEmpty();
    }

    private static String environmentId(DaiProperties.Environment env, String tier) {
        String lowerTier = tier.toLowerCase(java.util.Locale.ROOT);
        if (env.id() != null) {
            return env.id();
        }
        return (env.applicationName() != null ? env.applicationName() : "default") + "-" + lowerTier;
    }

    /**
     * Persistence-backed {@link AgentChatController.AgentResolver}: looks up published agent definitions
     * by slug from the latest config store snapshot. Results are cached per generation so the snapshot is
     * not re-parsed on every request.
     *
     * <p>Supersedes the no-op default registered by {@link DaiWebMvcAutoConfiguration}.
     *
     * @param configStore the config store
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(AgentSnapshotCache.class)
    @ConditionalOnBean(ConfigStore.class)
    public AgentSnapshotCache agentSnapshotCache(ConfigStore configStore, DaiProperties props,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations) {
        AgentSnapshotCache cache = new AgentSnapshotCache(configStore, props.store().maintenance().snapshotPollInterval());
        cache.observe(observations.getIfAvailable());
        return cache;
    }

    @Bean
    @ConditionalOnMissingBean(AgentChatController.AgentResolver.class)
    @ConditionalOnBean(AgentSnapshotCache.class)
    public AgentChatController.AgentResolver agentResolver(AgentSnapshotCache cache) {
        return cache::findBySlug;
    }

    /**
     * Persistence-backed {@link DispatchingBackingExecutor.AgentDefinitionResolver}: looks up published agent
     * definitions by UUID from the latest config store snapshot.
     *
     * <p>Supersedes the no-op default registered by {@link DaiWebMvcAutoConfiguration}.
     *
     * @param cache the shared snapshot cache
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.AgentDefinitionResolver.class)
    @ConditionalOnBean(AgentSnapshotCache.class)
    public DispatchingBackingExecutor.AgentDefinitionResolver agentDefinitionResolver(AgentSnapshotCache cache) {
        return cache::findById;
    }

    // ─── Query snapshot cache beans ──────────────────────────────────────────

    /**
     * Shared snapshot cache for published query definitions. Indexed by resource UUID;
     * generation-based so only one snapshot parse occurs per published generation.
     *
     * @param configStore the config store
     * @return the cache
     */
    @Bean
    @ConditionalOnMissingBean(QuerySnapshotCache.class)
    @ConditionalOnBean(ConfigStore.class)
    public QuerySnapshotCache querySnapshotCache(ConfigStore configStore, DaiProperties props,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations) {
        QuerySnapshotCache cache = new QuerySnapshotCache(configStore, props.store().maintenance().snapshotPollInterval());
        cache.observe(observations.getIfAvailable());
        return cache;
    }

    /**
     * Persistence-backed {@link DaiQueryAutoConfiguration.QueryDefinitionLoader}: loads published
     * query definitions by UUID from the latest config store snapshot.
     *
     * <p>Supersedes any no-op default registered with {@code @ConditionalOnMissingBean}.
     *
     * @param cache the shared query snapshot cache
     * @return the loader
     */
    @Bean
    @ConditionalOnMissingBean(DaiQueryAutoConfiguration.QueryDefinitionLoader.class)
    @ConditionalOnBean(QuerySnapshotCache.class)
    public DaiQueryAutoConfiguration.QueryDefinitionLoader queryDefinitionLoader(QuerySnapshotCache cache) {
        return cache::findById;
    }

    /**
     * Published tool bindings of the current generation (LLD-07 §2); polled by the maintenance runner.
     *
     * @param configStore the config store
     * @param props       framework properties (poll interval)
     * @return the cache
     */
    @Bean
    @ConditionalOnMissingBean(ToolBindingSnapshotCache.class)
    @ConditionalOnBean(ConfigStore.class)
    ToolBindingSnapshotCache toolBindingSnapshotCache(ConfigStore configStore, DaiProperties props,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations) {
        ToolBindingSnapshotCache cache = new ToolBindingSnapshotCache(configStore, props.store().maintenance().snapshotPollInterval());
        cache.observe(observations.getIfAvailable());
        return cache;
    }

    /**
     * Loads the tool bindings an agent references. Without this bean no {@code ToolBridge} exists and agents have no
     * tools.
     *
     * @param cache published bindings
     * @return the loader
     */
    @Bean
    @ConditionalOnMissingBean(ToolBridge.ToolBindingLoader.class)
    @ConditionalOnBean(ToolBindingSnapshotCache.class)
    ToolBridge.ToolBindingLoader toolBindingLoader(ToolBindingSnapshotCache cache) {
        return (bindingId, revision) -> cache.find(bindingId);
    }

    /**
     * Delegates for operation-backed tools: the same host-operation invocation the dynamic endpoints use, resolved
     * lazily so this bean does not depend on the web layer's declaration order.
     *
     * @param handlers the operation backing handler
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean(ToolBridge.OperationCallbackFactory.class)
    @ConditionalOnBean(ConfigStore.class)
    ToolBridge.OperationCallbackFactory operationCallbackFactory(
            org.springframework.beans.factory.ObjectProvider<DispatchingBackingExecutor.OperationBackingHandler> handlers) {
        return (operation, binding, principal) ->
                BackingToolCallback.forOperation(operation, binding, principal, handlers.getObject());
    }

    /**
     * Delegates for query-backed tools: the same compiled dynamic query the query endpoints run.
     *
     * @param handlers the query backing handler
     * @param loaders  loads the published query (for the tool's input schema)
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean(ToolBridge.QueryCallbackFactory.class)
    @ConditionalOnBean(ConfigStore.class)
    ToolBridge.QueryCallbackFactory queryCallbackFactory(
            org.springframework.beans.factory.ObjectProvider<DispatchingBackingExecutor.QueryBackingHandler> handlers,
            org.springframework.beans.factory.ObjectProvider<DaiQueryAutoConfiguration.QueryDefinitionLoader> loaders) {
        return (queryId, binding, principal) -> {
            DaiQueryAutoConfiguration.QueryDefinitionLoader loader = loaders.getIfAvailable();
            return BackingToolCallback.forQuery(queryId, loader == null ? null : loader.load(queryId), binding,
                    principal, handlers.getObject());
        };
    }

    /**
     * Background maintenance of this node (OQ-46): partition maintenance and retention on the cron, node heartbeat
     * with the applied generation, snapshot polling, stale-approval expiry, silent-node pruning and reconciliation
     * of proposals stuck in APPLYING. Off with {@code dynamic.ai.agent.store.maintenance.enabled=false}.
     *
     * <p>Declared after the stores and snapshot caches so its conditions see them.
     *
     * @param store     the persistence unit
     * @param config    config store (heartbeat, node pruning, approval expiry)
     * @param proposals proposal store (expiry, purge, apply reconciliation)
     * @param telemetry telemetry store (conversation purge inside partition maintenance)
     * @param views     snapshot caches to poll
     * @param props     framework properties
     * @return the runner
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(MaintenanceRunner.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "dynamic.ai.agent.store.maintenance", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnBean({DaiStore.class, ConfigStore.class, ChangeProposalStore.class, TelemetryStore.class})
    MaintenanceRunner maintenanceRunner(DaiStore store, ConfigStore config, ChangeProposalStore proposals,
                                        TelemetryStore telemetry,
                                        org.springframework.beans.factory.ObjectProvider<ApiKeyStore> apiKeys,
                                        org.springframework.beans.factory.ObjectProvider<SnapshotView> views,
                                        DaiProperties props) {
        DaiProperties.Maintenance settings = props.store().maintenance();
        java.time.Clock clock = java.time.Clock.systemUTC();
        String nodeId = settings.nodeId() != null ? settings.nodeId() : defaultNodeId();
        var partitions = new com.springaimcpservercommon.persistence.maintenance.PartitionMaintenance(
                store, proposals, telemetry, clock, nodeId, Map.of());
        java.util.List<SnapshotView> snapshotViews = views.orderedStream().toList();
        String app = props.environment().applicationName() != null ? props.environment().applicationName()
                : "application";
        String libraryVersion = MaintenanceRunner.class.getPackage().getImplementationVersion();
        var steps = new MaintenanceRunner.Steps(
                () -> snapshotViews.forEach(SnapshotView::refreshNow),
                () -> snapshotViews.stream().mapToLong(SnapshotView::loadedGeneration).filter(g -> g > 0).min()
                        .orElse(0L),
                config::heartbeat,
                () -> config.pruneNodes(settings.nodeRetention()),
                () -> config.expireApprovals(clock.instant().minus(settings.approvalTtl())),
                () -> failStuckApplies(proposals, settings.applyTimeout()),
                partitions::run,
                () -> {
                    ApiKeyStore keys = apiKeys.getIfAvailable();
                    return keys == null ? 0 : keys.countExpiringWithin(API_KEY_EXPIRY_WARNING);
                });
        return new MaintenanceRunner(steps, new MaintenanceRunner.Identity(nodeId, app, null,
                libraryVersion == null ? "unknown" : libraryVersion), settings, clock);
    }

    /** How far ahead the maintenance runner warns about API keys that are about to expire. */
    static final java.time.Duration API_KEY_EXPIRY_WARNING = java.time.Duration.ofDays(14);

    /**
     * Proposals in APPLYING longer than the timeout have an unknown outcome (the node applying them may have
     * crashed after the host write). They are failed with a fixed message so an operator verifies the host state;
     * a write is never retried automatically (ADR-0009).
     */
    static int failStuckApplies(ChangeProposalStore proposals, Duration timeout) {
        int failed = 0;
        for (java.util.UUID id : proposals.applyingSince(timeout, MaintenanceRunner.APPLY_BATCH)) {
            try {
                proposals.markFailed(id, "APPLY_TIMEOUT",
                        "The apply did not finish in time; check the host state before proposing again.");
                failed++;
            } catch (RuntimeException e) {
                // finished or failed concurrently on another node: nothing to reconcile
                LOG.debug("Proposal {} left APPLYING before reconciliation ({})", id, e.getClass().getSimpleName());
            }
        }
        return failed;
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | RuntimeException e) {
            host = "node";
        }
        String suffix = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(0x100000, 0xFFFFFF), 16);
        String id = host + "-" + suffix;
        return id.length() > 255 ? id.substring(id.length() - 255) : id;
    }

    /**
     * Real {@link DispatchingBackingExecutor.QueryBackingHandler}: loads the published
     * {@link QueryDefinition} from the snapshot cache and executes it via {@link QueryExecutor}.
     * Row policies are not yet applied (future: load from config store by entity + principal roles).
     *
     * <p>Supersedes the no-op default in {@link DaiWebMvcAutoConfiguration}.
     *
     * @param loader   loads QueryDefinition by UUID
     * @param executor executes the query against the host's JPA persistence unit
     * @param registry provides the current effective catalog
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean(DispatchingBackingExecutor.QueryBackingHandler.class)
    @ConditionalOnBean({DaiQueryAutoConfiguration.QueryDefinitionLoader.class,
                        QueryExecutor.class,
                        MetadataRegistry.class})
    public DispatchingBackingExecutor.QueryBackingHandler queryBackingHandler(
            DaiQueryAutoConfiguration.QueryDefinitionLoader loader,
            QueryExecutor executor,
            MetadataRegistry registry) {
        return (queryId, bindings, principal) -> {
            QueryDefinition def = loader.load(queryId);
            if (def == null) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.RESOURCE_SUSPENDED, "Query " + queryId + " is not published.");
            }
            try {
                QueryResult result = executor.execute(def, principal, bindings,
                        List.of(), registry.current(), 0);
                return queryResultToJson(result);
            } catch (QueryBulkheadException e) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.RATE_LIMITED, "Query engine at capacity.");
            } catch (jakarta.persistence.QueryTimeoutException e) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.EXECUTION_TIMEOUT, "Query timed out.");
            }
        };
    }

    private static String queryResultToJson(QueryResult result) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rows", result.rows());
        m.put("hasMore", result.hasMore());
        if (result.nextCursor() != null) {
            m.put("nextCursor", result.nextCursor());
        }
        m.put("rowCount", result.rowCount());
        return CanonicalJson.write(m);
    }

    // ─── Snapshot-backed resolver implementations ────────────────────────────

    /** What the maintenance runner needs from a snapshot cache. */
    interface SnapshotView {
        /** Checks the store for a newer generation now (ignores the throttle) and loads it. */
        void refreshNow();

        /** @return the generation currently served, 0 when nothing is loaded */
        long loadedGeneration();
    }

    /**
     * Shared snapshot cache: loads the config store snapshot once per generation and indexes agents
     * by both slug and resource UUID. Thread-safe; resolvers hold a reference to the same instance.
     */
    static final class AgentSnapshotCache implements SnapshotView {

        private final ConfigStore configStore;
        /** last generation we loaded; 0 = nothing cached. */
        private final AtomicLong cachedGeneration = new AtomicLong(0L);
        private volatile Map<String, AgentDefinition> bySlug = Map.of();
        private volatile Map<UUID, AgentDefinition> byId = Map.of();

        private final long maxAgeNanos;
        private final AtomicLong checkedAtNanos = new AtomicLong(System.nanoTime());
        private volatile boolean everChecked;
        private volatile @Nullable ObservationRegistry observations;

        /** Sets the host's registry for the {@code dai.snapshot.apply} span. */
        void observe(@Nullable ObservationRegistry registry) {
            this.observations = registry;
        }

        /**
         * @param configStore the config store
         * @param maxAge      longest a lookup trusts the cached generation before asking the store again
         *                    (the maintenance runner also polls, so idle nodes stay current)
         */
        AgentSnapshotCache(ConfigStore configStore, Duration maxAge) {
            this.configStore = configStore;
            this.maxAgeNanos = maxAge.toNanos();
        }

        @Override
        public long loadedGeneration() {
            return cachedGeneration.get();
        }

        @Override
        public void refreshNow() {
            refresh(true);
        }

        @Nullable AgentDefinition findBySlug(String slug) {
            refresh();
            return bySlug.get(slug);
        }

        @Nullable AgentDefinition findById(UUID id) {
            refresh();
            return byId.get(id);
        }

        private void refresh() {
            refresh(false);
        }

        private void refresh(boolean force) {
            long now = System.nanoTime();
            if (!force && everChecked && now - checkedAtNanos.get() < maxAgeNanos) return;
            checkedAtNanos.set(now);
            everChecked = true;
            long latest = configStore.latestGeneration().orElse(0L);
            if (latest <= cachedGeneration.get()) return;
            synchronized (this) {
                if (latest <= cachedGeneration.get()) return;
                SnapshotObservations.Span span = SnapshotObservations.open(observations, "agents", latest);
                try {
                Optional<PublishedSnapshot> snap = configStore.loadSnapshot(latest);
                if (snap.isEmpty()) {
                    span.outcome("missing");
                    return;
                }

                ConcurrentHashMap<String, AgentDefinition> slugMap = new ConcurrentHashMap<>();
                ConcurrentHashMap<UUID, AgentDefinition> idMap = new ConcurrentHashMap<>();

                for (PublishedResource pr : snap.get().resources()) {
                    if (pr.kind() != ResourceKind.AGENT) continue;
                    if (pr.resourceStatus() == ResourceStatus.SUSPENDED) continue;
                    try {
                        AgentDefinition def = parseAgent(pr);
                        slugMap.put(def.slug(), def);
                        idMap.put(def.id(), def);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse agent spec for resource {} (slug {}); skipping",
                                pr.resourceId(), pr.slug(), e);
                    }
                }

                bySlug = Map.copyOf(slugMap);
                byId = Map.copyOf(idMap);
                cachedGeneration.set(latest);
                LOG.debug("Agent snapshot refreshed: generation {}, {} agents indexed", latest, slugMap.size());
                } catch (RuntimeException e) {
                    span.fail(e);
                    throw e;
                } finally {
                    span.close();
                }
            }
        }
    }

    /**
     * Shared snapshot cache for published query definitions. Generation-based caching with
     * double-checked locking; indexes by resource UUID.
     */
    static final class QuerySnapshotCache implements SnapshotView {

        private final ConfigStore configStore;
        private final AtomicLong cachedGeneration = new AtomicLong(0L);
        private volatile Map<UUID, QueryDefinition> byId = Map.of();

        private final long maxAgeNanos;
        private final AtomicLong checkedAtNanos = new AtomicLong(System.nanoTime());
        private volatile boolean everChecked;
        private volatile @Nullable ObservationRegistry observations;

        /** Sets the host's registry for the {@code dai.snapshot.apply} span. */
        void observe(@Nullable ObservationRegistry registry) {
            this.observations = registry;
        }

        /**
         * @param configStore the config store
         * @param maxAge      longest a lookup trusts the cached generation before asking the store again
         *                    (the maintenance runner also polls, so idle nodes stay current)
         */
        QuerySnapshotCache(ConfigStore configStore, Duration maxAge) {
            this.configStore = configStore;
            this.maxAgeNanos = maxAge.toNanos();
        }

        @Override
        public long loadedGeneration() {
            return cachedGeneration.get();
        }

        @Override
        public void refreshNow() {
            refresh(true);
        }

        @Nullable QueryDefinition findById(UUID id) {
            refresh();
            return byId.get(id);
        }

        private void refresh() {
            refresh(false);
        }

        private void refresh(boolean force) {
            long now = System.nanoTime();
            if (!force && everChecked && now - checkedAtNanos.get() < maxAgeNanos) return;
            checkedAtNanos.set(now);
            everChecked = true;
            long latest = configStore.latestGeneration().orElse(0L);
            if (latest <= cachedGeneration.get()) return;
            synchronized (this) {
                if (latest <= cachedGeneration.get()) return;
                SnapshotObservations.Span span = SnapshotObservations.open(observations, "queries", latest);
                try {
                Optional<PublishedSnapshot> snap = configStore.loadSnapshot(latest);
                if (snap.isEmpty()) {
                    span.outcome("missing");
                    return;
                }

                ConcurrentHashMap<UUID, QueryDefinition> idMap = new ConcurrentHashMap<>();
                for (PublishedResource pr : snap.get().resources()) {
                    if (pr.kind() != ResourceKind.QUERY) continue;
                    if (pr.resourceStatus() == ResourceStatus.SUSPENDED) continue;
                    try {
                        QueryDefinition def = parseQuery(pr);
                        idMap.put(def.id(), def);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse query spec for resource {} (slug {}); skipping",
                                pr.resourceId(), pr.slug(), e);
                    }
                }

                byId = Map.copyOf(idMap);
                cachedGeneration.set(latest);
                LOG.debug("Query snapshot refreshed: generation {}, {} queries indexed", latest, idMap.size());
                } catch (RuntimeException e) {
                    span.fail(e);
                    throw e;
                } finally {
                    span.close();
                }
            }
        }
    }

    // ─── Agent spec JSON parsing ─────────────────────────────────────────────

    static AgentDefinition parseAgent(PublishedResource pr) {
        AgentSpecJson spec = SPEC_MAPPER.readerFor(AgentSpecJson.class)
                .readValue(pr.specJson());
        return new AgentDefinition(
                pr.resourceId(),
                pr.revisionNo(),
                pr.workspaceId(),
                pr.slug(),
                spec.displayName != null ? spec.displayName : pr.slug(),
                spec.systemPrompt != null ? spec.systemPrompt : "",
                toModelSelection(spec.model),
                toToolRefs(spec.tools),
                toMemorySpec(spec.memory),
                toGuardrailSpec(spec.guardrails),
                toLimitSpec(spec.limits),
                toOutputSpec(spec.output),
                toReferences(spec.references),
                spec.catalogHash != null ? spec.catalogHash : "sha256:unknown");
    }

    private static ModelSelection toModelSelection(@Nullable ModelSpecJson m) {
        if (m == null) return new ModelSelection("default", "default", null, null, null);
        ModelSelection fallback = m.fallback != null ? toModelSelection(m.fallback) : null;
        return new ModelSelection(
                m.providerId != null ? m.providerId : "default",
                m.modelName != null ? m.modelName : "default",
                m.temperature,
                m.maxTokens,
                fallback);
    }

    private static List<ToolBindingRef> toToolRefs(@Nullable List<ToolRefJson> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        return tools.stream()
                .filter(t -> t.bindingId != null)
                .map(t -> new ToolBindingRef(UUID.fromString(t.bindingId), t.revision))
                .toList();
    }

    private static MemorySpec toMemorySpec(@Nullable MemorySpecJson m) {
        if (m == null) return MemorySpec.NONE;
        MemorySpec.Strategy strategy;
        try {
            strategy = m.strategy != null
                    ? MemorySpec.Strategy.valueOf(m.strategy.toUpperCase(java.util.Locale.ROOT))
                    : MemorySpec.Strategy.NONE;
        } catch (IllegalArgumentException e) {
            strategy = MemorySpec.Strategy.NONE;
        }
        Duration retention = m.retentionSeconds != null && m.retentionSeconds > 0
                ? Duration.ofSeconds(m.retentionSeconds) : null;
        int windowSize = strategy == MemorySpec.Strategy.WINDOW ? Math.max(1, m.windowSize) : 0;
        return new MemorySpec(strategy, windowSize, retention);
    }

    private static GuardrailSpec toGuardrailSpec(@Nullable GuardrailSpecJson g) {
        if (g == null) return GuardrailSpec.OFF;
        return new GuardrailSpec(
                Math.max(0, g.maxInputChars),
                g.blockedPatterns != null ? g.blockedPatterns : List.of(),
                g.piiRedactionInput,
                g.piiRedactionOutput,
                g.topicAllowList != null ? g.topicAllowList : List.of(),
                Math.max(0, g.maxOutputChars));
    }

    private static LimitSpec toLimitSpec(@Nullable LimitSpecJson l) {
        if (l == null) return LimitSpec.DEFAULT;
        return new LimitSpec(
                Math.max(1, l.maxToolCallsPerTurn),
                Math.max(0, l.maxTokensPerTurn),
                Duration.ofSeconds(Math.max(5, l.turnTimeoutSeconds)),
                Math.max(0, l.maxTurnsPerConversation));
    }

    private static OutputSpec toOutputSpec(@Nullable OutputSpecJson o) {
        if (o == null) return OutputSpec.TEXT;
        OutputSpec.Mode mode;
        try {
            mode = o.mode != null
                    ? OutputSpec.Mode.valueOf(o.mode.toUpperCase(java.util.Locale.ROOT))
                    : OutputSpec.Mode.TEXT;
        } catch (IllegalArgumentException e) {
            mode = OutputSpec.Mode.TEXT;
        }
        return new OutputSpec(mode, o.jsonSchema);
    }

    private static Set<CatalogElementRef> toReferences(@Nullable List<RefJson> refs) {
        if (refs == null || refs.isEmpty()) return Set.of();
        return refs.stream()
                .filter(r -> r.kind != null && r.value != null)
                .map(r -> {
                    CatalogElementRef.Kind kind;
                    try {
                        kind = CatalogElementRef.Kind.valueOf(r.kind.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                    return new CatalogElementRef(kind, r.value);
                })
                .filter(ref -> ref != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String resolveTier(String configured) {
        return switch (configured.toUpperCase(java.util.Locale.ROOT)) {
            case "DEV" -> "DEV";
            case "TEST" -> "TEST";
            case "STAGE" -> "STAGE";
            case "PROD" -> "PROD";
            default -> "PROD"; // UNKNOWN → PROD (LLD-12 §2.1)
        };
    }

    // ─── Spec JSON DTOs ───────────────────────────────────────────────────────

    /** Jackson-deserializable form of the agent definition spec. Duration fields are in seconds. */
    static final class AgentSpecJson {
        public @Nullable String displayName;
        public @Nullable String systemPrompt;
        public @Nullable ModelSpecJson model;
        public @Nullable List<ToolRefJson> tools;
        public @Nullable MemorySpecJson memory;
        public @Nullable GuardrailSpecJson guardrails;
        public @Nullable LimitSpecJson limits;
        public @Nullable OutputSpecJson output;
        public @Nullable List<RefJson> references;
        public @Nullable String catalogHash;
    }

    static final class ModelSpecJson {
        public @Nullable String providerId;
        public @Nullable String modelName;
        public @Nullable Double temperature;
        public @Nullable Integer maxTokens;
        public @Nullable ModelSpecJson fallback;
    }

    static final class ToolRefJson {
        public @Nullable String bindingId;
        public int revision;
    }

    static final class MemorySpecJson {
        public @Nullable String strategy;
        public int windowSize;
        public @Nullable Long retentionSeconds;
    }

    static final class GuardrailSpecJson {
        public int maxInputChars;
        public @Nullable List<String> blockedPatterns;
        public boolean piiRedactionInput;
        public boolean piiRedactionOutput;
        public @Nullable List<String> topicAllowList;
        public int maxOutputChars;
    }

    static final class LimitSpecJson {
        public int maxToolCallsPerTurn = 10;
        public int maxTokensPerTurn = 4096;
        public long turnTimeoutSeconds = 60;
        public int maxTurnsPerConversation = 50;
    }

    static final class OutputSpecJson {
        public @Nullable String mode;
        public @Nullable String jsonSchema;
    }

    static final class RefJson {
        public @Nullable String kind;
        public @Nullable String value;
    }

    // ─── Query spec JSON parsing ─────────────────────────────────────────────

    static QueryDefinition parseQuery(PublishedResource pr) {
        QuerySpecJson spec = SPEC_MAPPER.readerFor(QuerySpecJson.class)
                .readValue(pr.specJson());

        CatalogElementRef root = spec.root != null
                ? CatalogElementRef.parse(spec.root)
                : CatalogElementRef.entity("unknown");

        List<Projection> select = toProjections(spec.select);
        FilterNode where = spec.where != null ? toFilterNode(spec.where) : null;
        List<SortSpec> orderBy = toSortSpecs(spec.orderBy);
        PageSpec page = toPageSpec(spec.page);
        List<QueryParam> params = toQueryParams(spec.params);
        Set<CatalogElementRef> references = toQueryReferences(spec.references);
        String catalogHash = spec.catalogHash != null ? spec.catalogHash : "sha256:unknown";

        return new QueryDefinition(
                pr.resourceId(),
                pr.revisionNo(),
                pr.workspaceId(),
                root,
                select,
                where,
                orderBy,
                page,
                params,
                references,
                catalogHash);
    }

    private static List<Projection> toProjections(@Nullable List<ProjectionJson> list) {
        if (list == null || list.isEmpty()) return List.of(new Projection(AttributePath.of("id")));
        return list.stream()
                .filter(p -> p.path != null)
                .map(p -> new Projection(AttributePath.parse(p.path), p.alias))
                .toList();
    }

    private static @Nullable FilterNode toFilterNode(@Nullable FilterNodeJson n) {
        if (n == null) return null;
        String type = n.type != null ? n.type.toLowerCase(Locale.ROOT) : "";
        return switch (type) {
            case "and" -> {
                List<FilterNodeJson> kids = n.children != null ? n.children : List.of();
                List<FilterNode> parsed = new ArrayList<>();
                for (FilterNodeJson k : kids) {
                    FilterNode fn = toFilterNode(k);
                    if (fn != null) parsed.add(fn);
                }
                if (parsed.isEmpty()) yield null;
                yield new FilterNode.And(parsed);
            }
            case "or" -> {
                List<FilterNodeJson> kids = n.children != null ? n.children : List.of();
                List<FilterNode> parsed = new ArrayList<>();
                for (FilterNodeJson k : kids) {
                    FilterNode fn = toFilterNode(k);
                    if (fn != null) parsed.add(fn);
                }
                if (parsed.isEmpty()) yield null;
                yield new FilterNode.Or(parsed);
            }
            case "not" -> {
                FilterNode child = toFilterNode(n.child);
                if (child == null) yield null;
                yield new FilterNode.Not(child);
            }
            case "cmp", "comparison" -> {
                if (n.path == null || n.op == null) yield null;
                Operator op;
                try {
                    op = Operator.valueOf(n.op.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    yield null;
                }
                Operand operand = n.operand != null ? toOperand(n.operand) : new Operand.Literal(null);
                yield new FilterNode.Comparison(AttributePath.parse(n.path), op, operand);
            }
            default -> null;
        };
    }

    private static Operand toOperand(OperandJson o) {
        String kind = o.kind != null ? o.kind.toLowerCase(Locale.ROOT) : "literal";
        return switch (kind) {
            case "param" -> new Operand.ParamRef(o.name != null ? o.name : "");
            case "principal" -> new Operand.PrincipalAttr(o.attr != null ? o.attr : "");
            default -> new Operand.Literal(o.value);
        };
    }

    private static List<SortSpec> toSortSpecs(@Nullable List<SortSpecJson> list) {
        if (list == null || list.isEmpty()) return List.of();
        return list.stream()
                .filter(s -> s.path != null)
                .map(s -> new SortSpec(AttributePath.parse(s.path), s.desc))
                .toList();
    }

    private static PageSpec toPageSpec(@Nullable PageSpecJson p) {
        if (p == null) return PageSpec.DEFAULT;
        int defaultSize = Math.max(1, p.defaultSize > 0 ? p.defaultSize : 20);
        int maxSize = Math.max(defaultSize, p.maxSize > 0 ? p.maxSize : 200);
        return new PageSpec(defaultSize, maxSize, p.keysetEnabled);
    }

    private static List<QueryParam> toQueryParams(@Nullable List<QueryParamJson> list) {
        if (list == null || list.isEmpty()) return List.of();
        return list.stream()
                .filter(p -> p.name != null)
                .map(p -> new QueryParam(
                        p.name,
                        p.schema != null ? p.schema : "{\"type\":\"string\"}",
                        p.required,
                        p.defaultValue))
                .toList();
    }

    private static Set<CatalogElementRef> toQueryReferences(@Nullable List<String> refs) {
        if (refs == null || refs.isEmpty()) return Set.of();
        return refs.stream()
                .filter(Objects::nonNull)
                .map(r -> {
                    try {
                        return CatalogElementRef.parse(r);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ─── Query spec JSON DTOs ─────────────────────────────────────────────────

    /** Jackson-deserializable form of a published query definition. */
    static final class QuerySpecJson {
        public @Nullable String root;
        public @Nullable List<ProjectionJson> select;
        public @Nullable FilterNodeJson where;
        public @Nullable List<SortSpecJson> orderBy;
        public @Nullable PageSpecJson page;
        public @Nullable List<QueryParamJson> params;
        public @Nullable List<String> references;
        public @Nullable String catalogHash;
    }

    static final class ProjectionJson {
        public @Nullable String path;
        public @Nullable String alias;
    }

    static final class FilterNodeJson {
        public @Nullable String type;
        public @Nullable List<FilterNodeJson> children;
        public @Nullable FilterNodeJson child;
        public @Nullable String path;
        public @Nullable String op;
        public @Nullable OperandJson operand;
    }

    static final class OperandJson {
        public @Nullable String kind;
        public @Nullable String name;
        public @Nullable Object value;
        public @Nullable String attr;
    }

    static final class SortSpecJson {
        public @Nullable String path;
        public boolean desc;
    }

    static final class PageSpecJson {
        public int defaultSize;
        public int maxSize;
        public boolean keysetEnabled = true;
    }

    static final class QueryParamJson {
        public @Nullable String name;
        public @Nullable String schema;
        public boolean required;
        public @Nullable Object defaultValue;
    }
}
