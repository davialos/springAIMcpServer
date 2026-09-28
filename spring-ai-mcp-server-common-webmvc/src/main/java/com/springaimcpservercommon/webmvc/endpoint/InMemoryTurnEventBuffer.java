package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.runtime.StreamEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Default in-memory {@link TurnEventBuffer}: stores the N most recent events per turn in a
 * circular buffer and retains completed turns until their TTL expires (LLD-13 §5).
 *
 * <p>Design notes:
 * <ul>
 *   <li>At most {@code maxEventsPerTurn} events are retained; when full, the oldest is dropped.</li>
 *   <li>A daemon thread runs cleanup every minute; expired turns are also removed lazily on {@link #since}.</li>
 *   <li>In multi-replica deployments, replace this with a PostgreSQL-backed bean to enable
 *       cross-replica replay (ADR-0021, {@code @ConditionalOnMissingBean}).</li>
 * </ul>
 *
 * <p>Not a Spring {@code @Component} — registered by {@code DaiWebMvcAutoConfiguration}.
 */
@NullMarked
public final class InMemoryTurnEventBuffer implements TurnEventBuffer, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTurnEventBuffer.class);

    /** Default maximum events stored per turn. */
    public static final int DEFAULT_MAX_EVENTS = 256;
    /** Default TTL after a turn is marked complete. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final int maxEventsPerTurn;
    private final long ttlNanos;
    private final ConcurrentHashMap<UUID, TurnSlot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    /**
     * Creates the buffer with default capacity and TTL.
     */
    public InMemoryTurnEventBuffer() {
        this(DEFAULT_MAX_EVENTS, DEFAULT_TTL);
    }

    /**
     * Creates the buffer with custom capacity and TTL.
     *
     * @param maxEventsPerTurn maximum events retained per turn before oldest are dropped
     * @param ttl              how long completed turns are retained before eviction
     */
    public InMemoryTurnEventBuffer(int maxEventsPerTurn, Duration ttl) {
        if (maxEventsPerTurn <= 0) throw new IllegalArgumentException("maxEventsPerTurn must be > 0");
        this.maxEventsPerTurn = maxEventsPerTurn;
        this.ttlNanos = ttl.toNanos();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dai-turn-buffer-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void append(UUID turnId, int seq, StreamEvent event) {
        TurnSlot slot = slots.computeIfAbsent(turnId, k -> new TurnSlot());
        synchronized (slot) {
            if (slot.events.size() >= maxEventsPerTurn) {
                slot.events.remove(0); // drop oldest to make room
            }
            slot.events.add(new BufferedEvent(seq, event));
        }
    }

    @Override
    public @Nullable List<BufferedEvent> since(UUID turnId, int afterSeq) {
        TurnSlot slot = slots.get(turnId);
        if (slot == null) return null;
        if (isExpired(slot)) {
            slots.remove(turnId, slot);
            return null;
        }
        synchronized (slot) {
            return slot.events.stream()
                    .filter(e -> e.seq() > afterSeq)
                    .toList();
        }
    }

    @Override
    public void complete(UUID turnId) {
        TurnSlot slot = slots.get(turnId);
        if (slot != null) {
            slot.complete = true;
            slot.completedAtNanos = System.nanoTime();
        }
    }

    @Override
    public void close() {
        cleaner.shutdown();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private boolean isExpired(TurnSlot slot) {
        return slot.complete
                && slot.completedAtNanos > 0
                && System.nanoTime() - slot.completedAtNanos > ttlNanos;
    }

    private void cleanup() {
        long now = System.nanoTime();
        int removed = 0;
        var it = slots.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            TurnSlot slot = entry.getValue();
            if (slot.complete && slot.completedAtNanos > 0 && now - slot.completedAtNanos > ttlNanos) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.debug("TurnEventBuffer cleanup: removed {} expired turn slots", removed);
        }
    }

    private static final class TurnSlot {
        final List<BufferedEvent> events = new ArrayList<>();
        volatile boolean complete;
        volatile long completedAtNanos = -1;
    }
}
