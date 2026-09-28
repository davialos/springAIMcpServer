package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.agent.ToolBindingRef;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.PublishedSnapshot;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceSettings;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.springaimcpservercommon.webmvc.endpoint.AgentChatController;
import com.springaimcpservercommon.webmvc.endpoint.DispatchingBackingExecutor;
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
import java.util.List;
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
        String envId = env.id() != null ? env.id()
                : (env.applicationName() != null ? env.applicationName() + "-" + tier.toLowerCase(java.util.Locale.ROOT)
                        : "default-" + tier.toLowerCase(java.util.Locale.ROOT));
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
}
