package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Workspaces and their direct memberships (F-62, SEC-01 §4).
 *
 * <p>Membership principals may be IdP groups, so the security module resolves the caller's user principal plus all
 * of its group principals and asks {@link #rolesFor} once per request (behind its cache).
 */
public final class WorkspaceStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public WorkspaceStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public WorkspaceStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates an active workspace.
     *
     * @param slug        unique slug ({@code [a-z][a-z0-9-]{1,62}})
     * @param name        display name
     * @param description optional description
     * @param tenantId    optional host tenant
     * @param clearance   highest classification allowed (not {@code INHERIT})
     * @param createdBy   creating principal, {@code null} for bootstrap
     * @return the created workspace
     */
    public WorkspaceView create(String slug, String name, @Nullable String description, @Nullable String tenantId,
                                Classification clearance, @Nullable UUID createdBy) {
        Workspace workspace = Workspace.create(slug, name, description, tenantId, clearance, createdBy, clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(workspace);
            em.flush();
            return workspace.view();
        });
    }

    /**
     * Updates name, description and clearance.
     *
     * @param id                  workspace id
     * @param expectedRowVersion  version the caller read
     * @param name                display name
     * @param description         optional description
     * @param clearance           clearance
     * @param updatedBy           acting principal
     * @return the updated workspace
     * @throws OptimisticLockException if the workspace changed since it was read
     * @throws NoSuchElementException  if it does not exist
     */
    public WorkspaceView update(UUID id, long expectedRowVersion, String name, @Nullable String description,
                                Classification clearance, UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Workspace workspace = require(em, id);
            if (workspace.getRowVersion() != expectedRowVersion) {
                throw new OptimisticLockException("workspace " + id + " was changed concurrently");
            }
            workspace.update(name, description, clearance, updatedBy, now);
            em.flush();
            return workspace.view();
        });
    }

    /**
     * Archives a workspace. Its memberships stop granting roles; nothing is deleted.
     *
     * @param id         workspace id
     * @param archivedBy acting principal
     * @throws NoSuchElementException if it does not exist
     */
    public void archive(UUID id, UUID archivedBy) {
        Instant now = clock.instant();
        store.transactions().executeWithoutResult(status -> require(store.entityManager(), id).archive(archivedBy, now));
    }

    /**
     * Finds a workspace by id.
     *
     * @param id workspace id
     * @return the workspace, if any
     */
    public Optional<WorkspaceView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(Workspace.class, id)).map(Workspace::view));
    }

    /**
     * Finds a workspace by slug.
     *
     * @param slug workspace slug
     * @return the workspace, if any
     */
    public Optional<WorkspaceView> findBySlug(String slug) {
        Objects.requireNonNull(slug, "slug");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select w from Workspace w where w.slug = :slug", Workspace.class)
                .setParameter("slug", slug)
                .getResultStream()
                .findFirst()
                .map(Workspace::view));
    }

    /**
     * Lists active workspaces ordered by slug.
     *
     * @return active workspaces
     */
    public List<WorkspaceView> listActive() {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select w from Workspace w where w.status = :status order by w.slug", Workspace.class)
                .setParameter("status", WorkspaceStatus.ACTIVE)
                .getResultStream()
                .map(Workspace::view)
                .toList());
    }

    /**
     * Adds a membership, or changes the expiry of an existing identical membership (idempotent).
     *
     * @param workspaceId workspace (must be active)
     * @param principalId member principal (user, group or service account)
     * @param role        workspace role (not a global-only role)
     * @param grantedBy   granting principal, {@code null} for bootstrap
     * @param expiresAt   optional expiry
     * @return the membership
     */
    public MemberView addMember(UUID workspaceId, UUID principalId, FrameworkRole role, @Nullable UUID grantedBy,
                                @Nullable Instant expiresAt) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            if (require(em, workspaceId).getStatus() != WorkspaceStatus.ACTIVE) {
                throw new IllegalStateException("workspace " + workspaceId + " is archived");
            }
            WorkspaceMember existing = em.find(WorkspaceMember.class, new WorkspaceMemberId(workspaceId, principalId, role));
            if (existing != null) {
                existing.changeExpiry(expiresAt);
                return existing.view();
            }
            WorkspaceMember member = WorkspaceMember.grant(workspaceId, principalId, role, grantedBy, expiresAt, now);
            em.persist(member);
            return member.view();
        });
    }

    /**
     * Removes a membership.
     *
     * @param workspaceId workspace
     * @param principalId member principal
     * @param role        role
     * @return {@code true} if a membership was removed
     */
    public boolean removeMember(UUID workspaceId, UUID principalId, FrameworkRole role) {
        Boolean removed = store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            WorkspaceMember member = em.find(WorkspaceMember.class, new WorkspaceMemberId(workspaceId, principalId, role));
            if (member == null) {
                return false;
            }
            em.remove(member);
            return true;
        });
        return Boolean.TRUE.equals(removed);
    }

    /**
     * Lists all memberships of a workspace, including expired ones (for access reviews, F-67).
     *
     * @param workspaceId workspace
     * @return memberships ordered by grant time
     */
    public List<MemberView> members(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select m from WorkspaceMember m where m.id.workspaceId = :ws order by m.grantedAt",
                        WorkspaceMember.class)
                .setParameter("ws", workspaceId)
                .getResultStream()
                .map(WorkspaceMember::view)
                .toList());
    }

    /**
     * Effective workspace roles of a caller: union over the given principals (the user and all of its groups) of
     * the non-expired memberships in active workspaces.
     *
     * @param principalIds principal ids of the caller (user, groups, service account)
     * @return roles per workspace id; empty when there is none
     */
    public Map<UUID, Set<FrameworkRole>> rolesFor(Collection<UUID> principalIds) {
        Objects.requireNonNull(principalIds, "principalIds");
        if (principalIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        List<WorkspaceMemberId> ids = store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select m.id from WorkspaceMember m, Workspace w"
                        + " where w.id = m.id.workspaceId and w.status = :active"
                        + " and m.id.principalId in :principals"
                        + " and (m.expiresAt is null or m.expiresAt > :now)", WorkspaceMemberId.class)
                .setParameter("active", WorkspaceStatus.ACTIVE)
                .setParameter("principals", Set.copyOf(principalIds))
                .setParameter("now", now)
                .getResultList());
        Map<UUID, Set<FrameworkRole>> roles = new HashMap<>();
        for (WorkspaceMemberId id : ids) {
            roles.computeIfAbsent(id.getWorkspaceId(), k -> EnumSet.noneOf(FrameworkRole.class)).add(id.getRole());
        }
        Map<UUID, Set<FrameworkRole>> copy = new HashMap<>();
        roles.forEach((ws, set) -> copy.put(ws, Set.copyOf(set)));
        return Map.copyOf(copy);
    }

    private static Workspace require(EntityManager em, UUID id) {
        Objects.requireNonNull(id, "id");
        Workspace workspace = em.find(Workspace.class, id);
        if (workspace == null) {
            throw new NoSuchElementException("workspace " + id + " does not exist");
        }
        return workspace;
    }
}
