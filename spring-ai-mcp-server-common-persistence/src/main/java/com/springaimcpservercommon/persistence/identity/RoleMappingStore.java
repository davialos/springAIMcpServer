package com.springaimcpservercommon.persistence.identity;

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
 * Database-managed role mappings (SEC-01 §3). Bootstrap mappings from configuration are merged by the security
 * module's {@code AuthorityMapper}; this store only owns the rows edited through the admin API.
 */
public final class RoleMappingStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public RoleMappingStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public RoleMappingStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates an enabled mapping. A duplicate rule (NULLs compare equal) violates {@code uq_role_mapping}.
     *
     * @param rule        matching rule
     * @param description optional description
     * @param createdBy   creating principal
     * @return the created mapping
     */
    public RoleMappingView create(RoleMappingRule rule, @Nullable String description, @Nullable UUID createdBy) {
        RoleMapping mapping = RoleMapping.create(rule, description, createdBy, clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(mapping);
            em.flush();
            return mapping.view();
        });
    }

    /**
     * Replaces rule and description.
     *
     * @param id                 mapping id
     * @param expectedRowVersion version the caller read
     * @param rule               new rule
     * @param description        new description
     * @param updatedBy          acting principal
     * @return the updated mapping
     * @throws OptimisticLockException if changed concurrently
     * @throws NoSuchElementException  if it does not exist
     */
    public RoleMappingView update(UUID id, long expectedRowVersion, RoleMappingRule rule, @Nullable String description,
                                  UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            RoleMapping mapping = require(em, id);
            if (mapping.getRowVersion() != expectedRowVersion) {
                throw new OptimisticLockException("role mapping " + id + " was changed concurrently");
            }
            mapping.update(rule, description, updatedBy, now);
            em.flush();
            return mapping.view();
        });
    }

    /**
     * Enables or disables a mapping.
     *
     * @param id        mapping id
     * @param enabled   target state
     * @param updatedBy acting principal
     * @return the mapping
     */
    public RoleMappingView setEnabled(UUID id, boolean enabled, UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            RoleMapping mapping = require(em, id);
            mapping.setEnabled(enabled, updatedBy, now);
            em.flush();
            return mapping.view();
        });
    }

    /**
     * Deletes a mapping (mappings carry no history; the admin API audits the change).
     *
     * @param id mapping id
     * @return {@code true} if deleted
     */
    public boolean delete(UUID id) {
        Boolean deleted = store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            RoleMapping mapping = em.find(RoleMapping.class, Objects.requireNonNull(id, "id"));
            if (mapping == null) {
                return false;
            }
            em.remove(mapping);
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * Finds a mapping.
     *
     * @param id mapping id
     * @return the mapping, if any
     */
    public Optional<RoleMappingView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(RoleMapping.class, id)).map(RoleMapping::view));
    }

    /**
     * All mappings, for the admin UI.
     *
     * @return mappings ordered by priority then id
     */
    public List<RoleMappingView> list() {
        return query(false);
    }

    /**
     * Enabled mappings in evaluation order (priority ascending, then id).
     *
     * @return enabled mappings
     */
    public List<RoleMappingView> enabledMappings() {
        return query(true);
    }

    private List<RoleMappingView> query(boolean enabledOnly) {
        String jpql = "select m from RoleMapping m" + (enabledOnly ? " where m.enabled = true" : "")
                + " order by m.priority, m.id";
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery(jpql, RoleMapping.class)
                .getResultStream()
                .map(RoleMapping::view)
                .toList());
    }

    private static RoleMapping require(EntityManager em, UUID id) {
        RoleMapping mapping = em.find(RoleMapping.class, Objects.requireNonNull(id, "id"));
        if (mapping == null) {
            throw new NoSuchElementException("role mapping " + id + " does not exist");
        }
        return mapping;
    }
}
