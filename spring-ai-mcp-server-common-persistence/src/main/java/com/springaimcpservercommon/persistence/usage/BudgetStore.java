package com.springaimcpservercommon.persistence.usage;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Budgets (F-70, LLD-10 §6). Enforcement (pre-turn check, reservations, alerts) lives in the AI runtime; this store
 * owns the definitions and tells which budgets apply to an invocation.
 */
public final class BudgetStore {

    /** Placeholder that matches no row, used instead of binding {@code null} into an equality. */
    private static final UUID NO_MATCH = new UUID(0L, 0L);

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public BudgetStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public BudgetStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates an enabled budget. A second budget for the same target and period violates {@code uq_budget}.
     *
     * @param target    whose usage is limited
     * @param period    DAY or MONTH
     * @param limits    limits
     * @param createdBy creating principal
     * @return the budget
     */
    public BudgetView create(BudgetTarget target, BudgetPeriod period, BudgetLimits limits, @Nullable UUID createdBy) {
        Budget budget = Budget.create(target, period, limits, createdBy, clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(budget);
            em.flush();
            return budget.view();
        });
    }

    /**
     * Replaces the limits of a budget.
     *
     * @param id                 budget id
     * @param expectedRowVersion version the caller read
     * @param limits             new limits
     * @param updatedBy          acting principal
     * @return the budget
     * @throws OptimisticLockException if changed concurrently
     */
    public BudgetView changeLimits(UUID id, long expectedRowVersion, BudgetLimits limits, UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Budget budget = require(em, id);
            if (budget.getRowVersion() != expectedRowVersion) {
                throw new OptimisticLockException("budget " + id + " was changed concurrently");
            }
            budget.changeLimits(limits, updatedBy, now);
            em.flush();
            return budget.view();
        });
    }

    /**
     * Enables or disables a budget.
     *
     * @param id        budget id
     * @param enabled   target state
     * @param updatedBy acting principal
     * @return the budget
     */
    public BudgetView setEnabled(UUID id, boolean enabled, UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Budget budget = require(em, id);
            budget.setEnabled(enabled, updatedBy, now);
            em.flush();
            return budget.view();
        });
    }

    /**
     * Deletes a budget.
     *
     * @param id budget id
     * @return {@code true} if deleted
     */
    public boolean delete(UUID id) {
        Boolean deleted = store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Budget budget = em.find(Budget.class, Objects.requireNonNull(id, "id"));
            if (budget == null) {
                return false;
            }
            em.remove(budget);
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * Finds a budget.
     *
     * @param id budget id
     * @return the budget, if any
     */
    public Optional<BudgetView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(Budget.class, id)).map(Budget::view));
    }

    /**
     * All budgets.
     *
     * @return budgets ordered by scope and creation time
     */
    public List<BudgetView> list() {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select b from Budget b order by b.scope, b.createdAt", Budget.class)
                .getResultStream()
                .map(Budget::view)
                .toList());
    }

    /**
     * Enabled budgets that apply to an invocation: GLOBAL, the workspace's, the agent's (if any) and the principal's
     * (unrestricted or restricted to this workspace).
     *
     * @param workspaceId     workspace of the invocation
     * @param agentResourceId agent, if the invocation is an agent turn
     * @param principalId     calling principal, if known
     * @return applicable budgets
     */
    public List<BudgetView> applicable(UUID workspaceId, @Nullable UUID agentResourceId, @Nullable UUID principalId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select b from Budget b where b.enabled = true and ("
                        + " b.scope = :global"
                        + " or (b.scope = :workspace and b.workspaceId = :ws)"
                        + " or (b.scope = :agent and b.agentResourceId = :agentId)"
                        + " or (b.scope = :principal and b.principalId = :principalId"
                        + "     and (b.workspaceId is null or b.workspaceId = :ws)))"
                        + " order by b.scope, b.createdAt", Budget.class)
                .setParameter("global", BudgetTarget.Scope.GLOBAL)
                .setParameter("workspace", BudgetTarget.Scope.WORKSPACE)
                .setParameter("agent", BudgetTarget.Scope.AGENT)
                .setParameter("principal", BudgetTarget.Scope.PRINCIPAL)
                .setParameter("ws", workspaceId)
                .setParameter("agentId", agentResourceId == null ? NO_MATCH : agentResourceId)
                .setParameter("principalId", principalId == null ? NO_MATCH : principalId)
                .getResultStream()
                .map(Budget::view)
                .toList());
    }

    private static Budget require(EntityManager em, UUID id) {
        Budget budget = em.find(Budget.class, Objects.requireNonNull(id, "id"));
        if (budget == null) {
            throw new NoSuchElementException("budget " + id + " does not exist");
        }
        return budget;
    }
}
