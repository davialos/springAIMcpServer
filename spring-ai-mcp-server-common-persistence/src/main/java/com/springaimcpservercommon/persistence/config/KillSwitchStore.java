package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Kill switches (F-73). Every node polls {@link #listActive()} on a fast interval (default 2 s, LLD-09 §4), so the
 * query only touches the small, indexed active set ({@code ix_kill_switch_active}).
 */
public final class KillSwitchStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public KillSwitchStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public KillSwitchStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Sets a kill switch.
     *
     * @param target    what to disable
     * @param reason    mandatory reason
     * @param setBy     acting principal
     * @param expiresAt optional automatic expiry
     * @return the new switch
     */
    public KillSwitchView set(KillSwitchTarget target, String reason, UUID setBy, @Nullable Instant expiresAt) {
        KillSwitch killSwitch = KillSwitch.set(target, reason, setBy, clock.instant(), expiresAt);
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(killSwitch);
            em.flush();
            return killSwitch.view();
        });
    }

    /**
     * Clears a kill switch. Idempotent.
     *
     * @param id        switch id
     * @param clearedBy acting principal
     * @return {@code true} if this call cleared an existing switch
     */
    public boolean clear(UUID id, UUID clearedBy) {
        Instant now = clock.instant();
        Boolean cleared = store.transactions().execute(status -> {
            KillSwitch killSwitch = store.entityManager().find(KillSwitch.class, Objects.requireNonNull(id, "id"));
            return killSwitch != null && killSwitch.clear(clearedBy, now);
        });
        return Boolean.TRUE.equals(cleared);
    }

    /**
     * Switches in force now: not cleared and not expired.
     *
     * @return active switches ordered by set time
     */
    public List<KillSwitchView> listActive() {
        Instant now = clock.instant();
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select k from KillSwitch k where k.clearedAt is null"
                        + " and (k.expiresAt is null or k.expiresAt > :now) order by k.setAt, k.id", KillSwitch.class)
                .setParameter("now", now)
                .getResultStream()
                .map(KillSwitch::view)
                .toList());
    }

    /**
     * Switch history (including cleared and expired ones) since a point in time, for the operations view.
     *
     * @param since earliest set time
     * @return switches ordered by set time, newest first
     */
    public List<KillSwitchView> history(Instant since) {
        Objects.requireNonNull(since, "since");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select k from KillSwitch k where k.setAt >= :since order by k.setAt desc", KillSwitch.class)
                .setParameter("since", since)
                .getResultStream()
                .map(KillSwitch::view)
                .toList());
    }
}
