package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Grants (SEC-01 §7). The authorization engine asks {@link #grantsFor} with all principal ids of the caller (user,
 * its IdP groups, service account) and evaluates permissions, patterns and ABAC conditions itself.
 */
public final class GrantStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public GrantStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public GrantStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a grant. An identical grant (same workspace, principal, permission and target) violates
     * {@code uq_grant}.
     *
     * @param workspaceId    workspace
     * @param principalId    grantee
     * @param permission     permission ({@code resource:action})
     * @param target         target
     * @param conditionsJson optional ABAC conditions (JSON object)
     * @param expiresAt      optional expiry
     * @param createdBy      granting principal
     * @return the grant
     */
    public GrantView create(UUID workspaceId, UUID principalId, String permission, GrantTarget target,
                            @Nullable String conditionsJson, @Nullable Instant expiresAt, UUID createdBy) {
        Grant grant = Grant.create(workspaceId, principalId, permission, target, conditionsJson, expiresAt, createdBy,
                clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(grant);
            em.flush();
            return grant.view();
        });
    }

    /**
     * Revokes (deletes) a grant; the admin API audits the revocation.
     *
     * @param grantId grant id
     * @return {@code true} if a grant was deleted
     */
    public boolean revoke(UUID grantId) {
        Boolean revoked = store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Grant grant = em.find(Grant.class, Objects.requireNonNull(grantId, "grantId"));
            if (grant == null) {
                return false;
            }
            em.remove(grant);
            return true;
        });
        return Boolean.TRUE.equals(revoked);
    }

    /**
     * Non-expired grants of a set of principals.
     *
     * @param principalIds caller's principal ids (user, groups, service account)
     * @param workspaceId  optional workspace restriction
     * @param resourceId   optional resource: when given, returns grants on that resource plus workspace-wide and
     *                     pattern grants (the caller matches patterns); when {@code null}, all grants
     * @return matching grants ordered by creation time
     */
    public List<GrantView> grantsFor(Collection<UUID> principalIds, @Nullable UUID workspaceId,
                                     @Nullable UUID resourceId) {
        Objects.requireNonNull(principalIds, "principalIds");
        if (principalIds.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        StringBuilder jpql = new StringBuilder("select g from DaiGrant g where g.principalId in :principals"
                + " and (g.expiresAt is null or g.expiresAt > :now)");
        if (workspaceId != null) {
            jpql.append(" and g.workspaceId = :ws");
        }
        if (resourceId != null) {
            jpql.append(" and (g.resourceId is null or g.resourceId = :resource)");
        }
        jpql.append(" order by g.createdAt, g.id");
        return store.readOnlyTransactions().execute(status -> {
            TypedQuery<Grant> query = store.entityManager().createQuery(jpql.toString(), Grant.class)
                    .setParameter("principals", Set.copyOf(principalIds))
                    .setParameter("now", now);
            if (workspaceId != null) {
                query.setParameter("ws", workspaceId);
            }
            if (resourceId != null) {
                query.setParameter("resource", resourceId);
            }
            return query.getResultStream().map(Grant::view).toList();
        });
    }

    /**
     * All grants of a workspace including expired ones (access reviews, F-67).
     *
     * @param workspaceId workspace
     * @return grants ordered by creation time
     */
    public List<GrantView> grantsInWorkspace(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select g from DaiGrant g where g.workspaceId = :ws order by g.createdAt, g.id", Grant.class)
                .setParameter("ws", workspaceId)
                .getResultStream()
                .map(Grant::view)
                .toList());
    }
}
