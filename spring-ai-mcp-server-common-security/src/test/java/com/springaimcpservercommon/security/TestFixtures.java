package com.springaimcpservercommon.security;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.authz.AuthorizationAuditListener;
import com.springaimcpservercommon.security.authz.AuthorizationDecisionEvent;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.MembershipSource;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;
import com.springaimcpservercommon.security.port.ResourceStatusView;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory fakes of the security ports and small builders shared by the tests. */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** A clock tests can move. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Principal directory keeping rows in a map; counts upserts. */
    public static final class InMemoryDirectory implements PrincipalDirectoryPort {
        public final Map<String, UUID> rows = new ConcurrentHashMap<>();
        public final AtomicInteger resolveCalls = new AtomicInteger();

        public UUID group(String issuer, String externalId) {
            return rows.computeIfAbsent(key(SubjectType.GROUP, issuer, externalId), k -> UUID.randomUUID());
        }

        @Override
        public UUID resolvePrincipalId(SubjectType type, String issuer, String externalId, @Nullable String displayName) {
            resolveCalls.incrementAndGet();
            return rows.computeIfAbsent(key(type, issuer, externalId), k -> UUID.randomUUID());
        }

        @Override
        public Map<String, UUID> findPrincipalIds(SubjectType type, String issuer, Set<String> externalIds) {
            Map<String, UUID> out = new HashMap<>();
            for (String id : externalIds) {
                UUID found = rows.get(key(type, issuer, id));
                if (found != null) {
                    out.put(id, found);
                }
            }
            return out;
        }

        private static String key(SubjectType type, String issuer, String externalId) {
            return type + "|" + issuer + "|" + externalId;
        }
    }

    /** Memberships by principal id. */
    public static final class InMemoryMemberships implements MembershipSource {
        public final Map<UUID, Map<UUID, Set<FrameworkRole>>> byPrincipal = new HashMap<>();

        public void add(UUID principalId, UUID workspaceId, FrameworkRole role) {
            byPrincipal.computeIfAbsent(principalId, k -> new HashMap<>())
                    .computeIfAbsent(workspaceId, k -> new java.util.HashSet<>()).add(role);
        }

        @Override
        public Map<UUID, Set<FrameworkRole>> findWorkspaceRoles(Set<UUID> principalIds, Instant at) {
            Map<UUID, Set<FrameworkRole>> out = new HashMap<>();
            for (UUID id : principalIds) {
                byPrincipal.getOrDefault(id, Map.of()).forEach((ws, roles) ->
                        out.computeIfAbsent(ws, k -> new java.util.HashSet<>()).addAll(roles));
            }
            return out;
        }
    }

    /** Grants in a list. */
    public static final class InMemoryGrants implements GrantSource {
        public final List<GrantRecord> grants = new ArrayList<>();

        public GrantRecord add(UUID workspaceId, UUID principalId, String permission, @Nullable UUID resourceId,
                               @Nullable String pattern, @Nullable String conditions, @Nullable Instant expiresAt) {
            GrantRecord grant = new GrantRecord(UUID.randomUUID(), workspaceId, principalId, permission, resourceId,
                    pattern, conditions, expiresAt);
            grants.add(grant);
            return grant;
        }

        @Override
        public List<GrantRecord> findGrants(UUID workspaceId, Set<UUID> principalIds, String permission) {
            return grants.stream()
                    .filter(g -> g.workspaceId().equals(workspaceId) && principalIds.contains(g.principalId())
                            && g.permission().equals(permission))
                    .toList();
        }
    }

    /** Kill switches. */
    public static final class InMemoryKillSwitches implements KillSwitchView {
        public @Nullable ActiveKillSwitch global;
        public final Map<UUID, ActiveKillSwitch> byResource = new HashMap<>();
        public final Map<String, ActiveKillSwitch> byTool = new HashMap<>();

        @Override
        public Optional<ActiveKillSwitch> findActive(@Nullable UUID workspaceId, @Nullable UUID resourceId,
                                                     @Nullable String toolName, Instant at) {
            if (global != null) {
                return Optional.of(global);
            }
            if (resourceId != null && byResource.containsKey(resourceId)) {
                return Optional.of(byResource.get(resourceId));
            }
            if (toolName != null && byTool.containsKey(toolName)) {
                return Optional.of(byTool.get(toolName));
            }
            return Optional.empty();
        }
    }

    /** Resource states; default PUBLISHED. */
    public static final class InMemoryResourceStatus implements ResourceStatusView {
        public final Map<UUID, ResourceStatus> states = new HashMap<>();

        @Override
        public ResourceStatus statusOf(UUID resourceId) {
            return states.getOrDefault(resourceId, ResourceStatus.PUBLISHED);
        }
    }

    /** Collects audit events. */
    public static final class RecordingAudit implements AuthorizationAuditListener {
        public final List<AuthorizationDecisionEvent> events = new ArrayList<>();

        @Override
        public void onDecision(AuthorizationDecisionEvent event) {
            events.add(event);
        }
    }

    /**
     * A user principal.
     */
    public static DaiPrincipal user(UUID principalId, Set<String> groups, Set<FrameworkRole> globalRoles,
                                    Map<UUID, Set<FrameworkRole>> workspaceRoles, Map<String, Object> attributes,
                                    Classification clearance, Set<String> scopes) {
        return new DaiPrincipal(principalId, SubjectType.USER, "https://idp.example.com", "subject-" + principalId,
                null, groups, globalRoles, workspaceRoles, attributes, clearance, null, scopes);
    }

    /**
     * A plain user without roles.
     */
    public static DaiPrincipal plainUser() {
        return user(UUID.randomUUID(), Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, Set.of());
    }
}
