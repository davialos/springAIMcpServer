package com.springaimcpservercommon.persistence.memory;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import org.jspecify.annotations.NullMarked;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL store for the model's chat memory (OQ-45, migration V9). Every operation is one transaction, so any
 * replica sees the same window; nothing is held in server memory (ADR-0021). Content must already be redacted.
 */
@NullMarked
public final class ChatMemoryStore {

    /** Most messages one memory keeps. */
    public static final int MAX_MESSAGES = 1_000;

    /** Largest batch for {@link #purgeExpired(int)}. */
    public static final int MAX_PURGE_BATCH = 10_000;

    /**
     * A message to remember.
     *
     * @param role    who said it
     * @param content redacted text
     */
    public record Entry(ChatMemoryMessage.Role role, String content) {
        /** Validates the components. */
        public Entry {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
        }
    }

    private final StoreSupport db;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param store the framework's persistence unit
     * @param clock time source
     */
    public ChatMemoryStore(DaiStore store, Clock clock) {
        this.db = new StoreSupport(store);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the live messages of a memory, oldest first; an expired memory reads as empty.
     *
     * @param memoryKey {@code sha256:<hex>} of the conversation key
     * @return the messages
     */
    public List<Entry> load(String memoryKey) {
        Checks.sha256(memoryKey, "memoryKey");
        Instant now = clock.instant();
        return db.read(em -> em.createQuery("select m from ChatMemoryMessage m where m.memoryKey = :key "
                        + "and m.expiresAt > :now order by m.seq", ChatMemoryMessage.class)
                .setParameter("key", memoryKey)
                .setParameter("now", now)
                .getResultList().stream()
                .map(m -> new Entry(m.getRole(), m.getContent()))
                .toList());
    }

    /**
     * Replaces a memory as a whole ({@code saveAll} semantics of Spring AI's {@code ChatMemoryRepository}) and slides
     * its expiry to {@code now + ttl}. An empty list deletes the memory.
     *
     * @param memoryKey {@code sha256:<hex>} of the conversation key
     * @param entries   the complete new window (already redacted)
     * @param ttl       how long the memory lives from now
     */
    public void replace(String memoryKey, List<Entry> entries, Duration ttl) {
        Checks.sha256(memoryKey, "memoryKey");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("at most " + MAX_MESSAGES + " messages per memory");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        db.writeVoid(em -> {
            deleteRows(em, memoryKey);
            for (int i = 0; i < entries.size(); i++) {
                em.persist(ChatMemoryMessage.of(memoryKey, i, entries.get(i), now, expiresAt));
            }
        });
    }

    /**
     * Deletes a memory.
     *
     * @param memoryKey {@code sha256:<hex>} of the conversation key
     */
    public void delete(String memoryKey) {
        Checks.sha256(memoryKey, "memoryKey");
        db.writeVoid(em -> deleteRows(em, memoryKey));
    }

    // native: ChatMemoryMessage is @Immutable and Hibernate refuses HQL mutation of immutable entities
    private void deleteRows(jakarta.persistence.EntityManager em, String memoryKey) {
        em.createNativeQuery("DELETE FROM " + db.qualified("dai_chat_memory_message") + " WHERE memory_key = ?1")
                .setParameter(1, memoryKey)
                .executeUpdate();
    }

    /**
     * Deletes messages of expired memories. Bounded per call so a large backlog never holds a long transaction.
     *
     * @param batchSize most rows to delete, 1–{@value #MAX_PURGE_BATCH}
     * @return rows deleted
     */
    public int purgeExpired(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_PURGE_BATCH) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_PURGE_BATCH);
        }
        Instant now = clock.instant();
        String table = db.qualified("dai_chat_memory_message");
        return db.write(em -> em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE expires_at <= ?1 ORDER BY expires_at LIMIT ?2 FOR UPDATE SKIP LOCKED)")
                .setParameter(1, now)
                .setParameter(2, batchSize)
                .executeUpdate());
    }
}
