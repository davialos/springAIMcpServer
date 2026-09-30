package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.GrantStore;
import com.springaimcpservercommon.persistence.config.GrantTarget;
import com.springaimcpservercommon.persistence.config.GrantView;
import com.springaimcpservercommon.persistence.config.KillSwitchStore;
import com.springaimcpservercommon.persistence.config.KillSwitchTarget;
import com.springaimcpservercommon.persistence.config.KillSwitchView;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.PublishedSnapshot;
import com.springaimcpservercommon.persistence.identity.ApiKeyStore;
import com.springaimcpservercommon.persistence.identity.ApiKeyView;
import com.springaimcpservercommon.persistence.identity.McpClientStore;
import com.springaimcpservercommon.persistence.identity.McpClientView;
import com.springaimcpservercommon.persistence.identity.McpConsentScope;
import com.springaimcpservercommon.persistence.identity.PrincipalDirectory;
import com.springaimcpservercommon.persistence.identity.PrincipalStatus;
import com.springaimcpservercommon.persistence.identity.PrincipalView;
import com.springaimcpservercommon.persistence.identity.ResolvedPrincipal;
import com.springaimcpservercommon.persistence.identity.RoleMappingStore;
import com.springaimcpservercommon.persistence.identity.RoleMappingView;
import com.springaimcpservercommon.persistence.identity.ServiceAccountStatus;
import com.springaimcpservercommon.persistence.identity.ServiceAccountView;
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.security.port.ApiKeyLookup;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.McpClientRegistryPort;
import com.springaimcpservercommon.security.port.MembershipSource;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;
import com.springaimcpservercommon.security.port.ResourceStatusView;
import com.springaimcpservercommon.security.port.RoleMappingSource;
import com.springaimcpservercommon.security.principal.MappingSource;
import com.springaimcpservercommon.security.principal.PrincipalMappingException;
import com.springaimcpservercommon.security.principal.RoleMappingRule;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@code dynamic_ai} store implementations of the ports the authorization engine and the principal mapper are
 * written against ({@code security.port}). Without them no {@code AuthorizationEngine} or {@code AuthorityMapper}
 * bean exists, and with those absent the whole HTTP layer (admin API, agent chat, dynamic endpoints) stays
 * unregistered. Each adapter is a thin translation of a store read; the caches are node-local and short-lived
 * (ADR-0021), so a revoked grant or a new kill switch takes effect within seconds on every node.
 */
@NullMarked
final class StoreSecurityPorts {

    private static final Logger LOG = LoggerFactory.getLogger(StoreSecurityPorts.class);
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private StoreSecurityPorts() {
    }

    /** Principal directory over {@code dai_principal}; a disabled subject can never be mapped. */
    static final class Directory implements PrincipalDirectoryPort {
        private final PrincipalDirectory directory;

        Directory(PrincipalDirectory directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        @Override
        public UUID resolvePrincipalId(SubjectType type, String issuer, String externalId,
                                       @Nullable String displayName) {
            ResolvedPrincipal resolved;
            try {
                resolved = directory.resolveSubject(type, issuer, externalId, displayName);
            } catch (IllegalArgumentException e) {
                throw new PrincipalMappingException("the subject identity is not acceptable");
            }
            if (!resolved.active()) {
                throw new PrincipalMappingException("the principal is disabled");
            }
            return resolved.id();
        }

        @Override
        public Map<String, UUID> findPrincipalIds(SubjectType type, String issuer, Set<String> externalIds) {
            Map<String, UUID> found = new HashMap<>();
            for (String externalId : externalIds) {
                try {
                    Optional<PrincipalView> view = directory.findBySubject(type, issuer, externalId);
                    if (view.isPresent() && view.get().status() == PrincipalStatus.ACTIVE) {
                        found.put(externalId, view.get().id());
                    }
                } catch (IllegalArgumentException e) {
                    // an identifier the directory would never hold (too long, control characters): no principal
                }
            }
            return found;
        }
    }

    /** Workspace roles from {@code dai_workspace_member} (non-expired memberships of active workspaces). */
    static final class Memberships implements MembershipSource {
        private final WorkspaceStore workspaces;

        Memberships(WorkspaceStore workspaces) {
            this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        }

        @Override
        public Map<UUID, Set<FrameworkRole>> findWorkspaceRoles(Set<UUID> principalIds, Instant at) {
            return workspaces.rolesFor(principalIds);
        }
    }

    /** Enabled IdP-to-role mappings from {@code dai_role_mapping}, reloaded every few seconds. */
    static final class RoleMappings implements RoleMappingSource {
        private final TtlSnapshot<List<RoleMappingRule>> snapshot;

        RoleMappings(RoleMappingStore store, Duration ttl, Clock clock) {
            Objects.requireNonNull(store, "store");
            this.snapshot = new TtlSnapshot<>("role mappings", () -> load(store), ttl, clock);
        }

        @Override
        public List<RoleMappingRule> findEnabledMappings() {
            return snapshot.get();
        }

        private static List<RoleMappingRule> load(RoleMappingStore store) {
            List<RoleMappingRule> rules = new ArrayList<>();
            for (RoleMappingView view : store.enabledMappings()) {
                var r = view.rule();
                try {
                    rules.add(new RoleMappingRule(MappingSource.valueOf(r.source().name()), r.issuer(),
                            r.claimName(), r.matchValue(), r.role(), r.workspaceId(), r.priority()));
                } catch (IllegalArgumentException e) {
                    LOG.warn("Ignoring role mapping {}: {}", view.id(), e.getMessage());
                }
            }
            return List.copyOf(rules);
        }
    }

    /** Grants from {@code dai_grant}, cached briefly per (workspace, principals, permission). */
    static final class Grants implements GrantSource {
        private record Key(UUID workspaceId, Set<UUID> principalIds, String permission) {}

        private record Entry(List<GrantRecord> grants, Instant expiresAt) {}

        private final GrantStore store;
        private final Duration ttl;
        private final Clock clock;
        private final ConcurrentMap<Key, Entry> cache = new ConcurrentHashMap<>();

        Grants(GrantStore store, Duration ttl, Clock clock) {
            this.store = Objects.requireNonNull(store, "store");
            this.ttl = Objects.requireNonNull(ttl, "ttl");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public List<GrantRecord> findGrants(UUID workspaceId, Set<UUID> principalIds, String permission) {
            Key key = new Key(workspaceId, Set.copyOf(principalIds), permission);
            Instant now = clock.instant();
            Entry cached = cache.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return cached.grants();
            }
            List<GrantRecord> grants = new ArrayList<>();
            for (GrantView g : store.grantsFor(principalIds, workspaceId, null)) {
                if (!g.permission().equals(permission)) {
                    continue;
                }
                UUID resourceId = g.target() instanceof GrantTarget.OnResource r ? r.resourceId() : null;
                String pattern = g.target() instanceof GrantTarget.OnPattern p ? p.pattern() : null;
                grants.add(new GrantRecord(g.id(), g.workspaceId(), g.principalId(), g.permission(), resourceId,
                        pattern, g.conditionsJson(), g.expiresAt()));
            }
            List<GrantRecord> result = List.copyOf(grants);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Entry(result, now.plus(ttl)));
            return result;
        }

        @Override
        public void invalidateAll() {
            cache.clear();
        }
    }

    /** Active kill switches from {@code dai_kill_switch}, reloaded every couple of seconds (F-73: effective in 10 s). */
    static final class KillSwitches implements com.springaimcpservercommon.security.port.KillSwitchView {
        private final TtlSnapshot<List<KillSwitchView>> snapshot;

        KillSwitches(KillSwitchStore store, Duration ttl, Clock clock) {
            this(new TtlSnapshot<>("kill switches", Objects.requireNonNull(store, "store")::listActive, ttl, clock));
        }

        KillSwitches(TtlSnapshot<List<KillSwitchView>> snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public Optional<ActiveKillSwitch> findActive(@Nullable UUID workspaceId, @Nullable UUID resourceId,
                                                     @Nullable String toolName, Instant at) {
            ActiveKillSwitch best = null;
            for (KillSwitchView k : snapshot.get()) {
                if (!k.activeAt(at) || !matches(k.target(), workspaceId, resourceId, toolName)) {
                    continue;
                }
                Scope scope = Scope.valueOf(k.target().scope().name());
                if (best == null || scope.ordinal() < best.scope().ordinal()) {
                    best = new ActiveKillSwitch(k.id(), scope);
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * Whether a switch applies to a request. A workspace on the switch, when present, must equal the
         * request's; a switch without one applies in every workspace.
         */
        static boolean matches(KillSwitchTarget target, @Nullable UUID workspaceId, @Nullable UUID resourceId,
                               @Nullable String toolName) {
            return switch (target) {
                case KillSwitchTarget.Global _ -> true;
                case KillSwitchTarget.Workspace w -> w.workspaceId().equals(workspaceId);
                case KillSwitchTarget.Resource r -> r.resourceId().equals(resourceId)
                        && (r.workspaceId() == null || r.workspaceId().equals(workspaceId));
                case KillSwitchTarget.Tool t -> t.toolName().equals(toolName)
                        && (t.workspaceId() == null || t.workspaceId().equals(workspaceId));
            };
        }
    }

    /**
     * Resource publication status. Live resources come from the latest published snapshot (reloaded when the
     * generation changes, checked at most every few seconds); anything else is looked up once and cached briefly.
     */
    static final class ResourceStatuses implements ResourceStatusView {
        private record Loaded(long generation, Map<UUID, ResourceStatus> byId) {}

        private record Fallback(ResourceStatus status, Instant expiresAt) {}

        private final ConfigStore configStore;
        private final Duration ttl;
        private final Clock clock;
        private final TtlSnapshot<Loaded> live;
        private final ConcurrentMap<UUID, Fallback> fallback = new ConcurrentHashMap<>();

        ResourceStatuses(ConfigStore configStore, Duration ttl, Clock clock) {
            this.configStore = Objects.requireNonNull(configStore, "configStore");
            this.ttl = Objects.requireNonNull(ttl, "ttl");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.live = new TtlSnapshot<>("resource statuses", this::load, ttl, clock);
        }

        @Override
        public ResourceStatus statusOf(UUID resourceId) {
            ResourceStatus status = live.get().byId().get(resourceId);
            if (status != null) {
                return status;
            }
            Instant now = clock.instant();
            Fallback cached = fallback.get(resourceId);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return cached.status();
            }
            ResourceStatus looked = configStore.findResource(resourceId).map(r -> switch (r.status()) {
                case ACTIVE -> ResourceStatus.NOT_PUBLISHED;
                case SUSPENDED -> ResourceStatus.SUSPENDED;
                case RETIRED -> ResourceStatus.RETIRED;
            }).orElse(ResourceStatus.UNKNOWN);
            if (fallback.size() >= MAX_CACHE_ENTRIES) {
                fallback.clear();
            }
            fallback.put(resourceId, new Fallback(looked, now.plus(ttl)));
            return looked;
        }

        /**
         * Drops what this node knows so a publish, suspend or retire made through this node is seen by the very
         * next request; other nodes converge within the TTL.
         */
        void invalidateAll() {
            fallback.clear();
            live.invalidate();
        }

        private Loaded load() {
            long latest = configStore.latestGeneration().orElse(0L);
            Map<UUID, ResourceStatus> byId = new HashMap<>();
            if (latest > 0) {
                Optional<PublishedSnapshot> snapshot = configStore.loadSnapshot(latest);
                if (snapshot.isPresent()) {
                    for (PublishedResource pr : snapshot.get().resources()) {
                        byId.put(pr.resourceId(), switch (pr.resourceStatus()) {
                            case ACTIVE -> ResourceStatus.PUBLISHED;
                            case SUSPENDED -> ResourceStatus.SUSPENDED;
                            case RETIRED -> ResourceStatus.RETIRED;
                        });
                    }
                }
            }
            return new Loaded(latest, Map.copyOf(byId));
        }
    }

    /** API keys and their service accounts, for the API key filter. */
    static final class ApiKeys implements ApiKeyLookup {
        private final ApiKeyStore store;

        ApiKeys(ApiKeyStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
            Optional<ApiKeyView> key = store.findActiveByPrefix(keyPrefix);
            if (key.isEmpty()) {
                return Optional.empty();
            }
            ApiKeyView k = key.get();
            Optional<ServiceAccountView> account = store.findServiceAccount(k.serviceAccountId());
            if (account.isEmpty()) {
                return Optional.empty();
            }
            ServiceAccountView sa = account.get();
            return Optional.of(new ApiKeyRecord(k.id(), k.keyPrefix(), k.keyHash(), k.hashAlgorithm().dbValue(),
                    k.expiresAt(), k.revokedAt(), k.lastUsedAt(), sa.id(), sa.principalId(), sa.name(),
                    sa.status() == ServiceAccountStatus.ACTIVE, k.workspaceId(), k.scopes(), k.allowedNetworks()));
        }

        @Override
        public void touchLastUsed(UUID apiKeyId, Instant at) {
            store.touchLastUsed(apiKeyId);
        }
    }

    /** Approved MCP clients and user consents (F-55). */
    static final class McpClients implements McpClientRegistryPort {
        private final McpClientStore store;

        McpClients(McpClientStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public Optional<UUID> findApprovedClient(UUID workspaceId, String issuer, String clientId) {
            return store.findApproved(issuer, clientId).stream()
                    .filter(c -> c.workspaceId().equals(workspaceId))
                    .map(McpClientView::id)
                    .findFirst();
        }

        @Override
        public boolean hasActiveConsent(UUID mcpClientId, UUID principalId) {
            return store.activeConsent(mcpClientId, principalId).isPresent();
        }

        @Override
        public void recordConsent(UUID mcpClientId, UUID principalId, Set<String> scopes) {
            Set<McpConsentScope> mapped = new TreeSet<>();
            for (String scope : scopes) {
                try {
                    mapped.add(McpConsentScope.fromValue(scope));
                } catch (IllegalArgumentException e) {
                    LOG.warn("Ignoring unknown MCP consent scope while recording consent for client {}", mcpClientId);
                }
            }
            store.grantConsent(mcpClientId, principalId, mapped);
        }
    }
}
