package com.springaimcpservercommon.autoconfigure;

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
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
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
@AutoConfiguration(after = DaiCoreAutoConfiguration.class)
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
        DaiPersistenceSettings settings = DaiPersistenceSettings.defaults(envId, tier);
        LOG.info("Starting dynamic_ai persistence unit (schema {}, env {}/{})",
                settings.schema(), envId, tier);
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
    public AgentSnapshotCache agentSnapshotCache(ConfigStore configStore) {
        return new AgentSnapshotCache(configStore);
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
    public QuerySnapshotCache querySnapshotCache(ConfigStore configStore) {
        return new QuerySnapshotCache(configStore);
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

    /**
     * Shared snapshot cache: loads the config store snapshot once per generation and indexes agents
     * by both slug and resource UUID. Thread-safe; resolvers hold a reference to the same instance.
     */
    static final class AgentSnapshotCache {

        private final ConfigStore configStore;
        /** last generation we loaded; 0 = nothing cached. */
        private final AtomicLong cachedGeneration = new AtomicLong(0L);
        private volatile Map<String, AgentDefinition> bySlug = Map.of();
        private volatile Map<UUID, AgentDefinition> byId = Map.of();

        AgentSnapshotCache(ConfigStore configStore) {
            this.configStore = configStore;
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
            long latest = configStore.latestGeneration().orElse(0L);
            if (latest <= cachedGeneration.get()) return;
            synchronized (this) {
                if (latest <= cachedGeneration.get()) return;
                Optional<PublishedSnapshot> snap = configStore.loadSnapshot(latest);
                if (snap.isEmpty()) return;

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
            }
        }
    }

    /**
     * Shared snapshot cache for published query definitions. Generation-based caching with
     * double-checked locking; indexes by resource UUID.
     */
    static final class QuerySnapshotCache {

        private final ConfigStore configStore;
        private final AtomicLong cachedGeneration = new AtomicLong(0L);
        private volatile Map<UUID, QueryDefinition> byId = Map.of();

        QuerySnapshotCache(ConfigStore configStore) {
            this.configStore = configStore;
        }

        @Nullable QueryDefinition findById(UUID id) {
            refresh();
            return byId.get(id);
        }

        private void refresh() {
            long latest = configStore.latestGeneration().orElse(0L);
            if (latest <= cachedGeneration.get()) return;
            synchronized (this) {
                if (latest <= cachedGeneration.get()) return;
                Optional<PublishedSnapshot> snap = configStore.loadSnapshot(latest);
                if (snap.isEmpty()) return;

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
