package com.springaimcpservercommon.mockdata.ai;

import com.springaimcpservercommon.mockdata.engine.FieldGap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps AI-generated sample objects per type and hands them out round-robin, so a load test of millions of requests
 * costs a handful of model calls. Bounded by a call budget, protected by a circuit breaker (a failing model is skipped
 * for a cool-down), and never throws: when no sample is available the caller falls back to Datafaker values.
 * Thread-safe.
 */
public final class AiMockPool {

    private static final Logger LOG = LoggerFactory.getLogger(AiMockPool.class);

    /**
     * Pool settings.
     *
     * @param batchSize        objects requested per model call
     * @param maxCalls         model calls allowed over the lifetime of the pool (the budget); afterwards samples are only reused
     * @param failureThreshold consecutive failures that open the circuit
     * @param cooldown         how long the circuit stays open
     */
    public record Settings(int batchSize, int maxCalls, int failureThreshold, Duration cooldown) {

        /** Validates. */
        public Settings {
            if (batchSize < 1 || maxCalls < 0 || failureThreshold < 1) {
                throw new IllegalArgumentException("batchSize and failureThreshold must be at least 1, maxCalls at least 0");
            }
            Objects.requireNonNull(cooldown, "cooldown");
        }

        /**
         * Defaults: 20 objects per call, 50 calls, circuit opens after 3 failures for 1 minute.
         *
         * @return settings
         */
        public static Settings defaults() {
            return new Settings(20, 50, 3, Duration.ofMinutes(1));
        }
    }

    private static final class Entry {
        volatile List<Object> samples = List.of();
        final AtomicInteger cursor = new AtomicInteger();
    }

    private final AiMockGenerator generator;
    private final Settings settings;
    private final Clock clock;
    private final Map<Class<?>, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile @Nullable Instant openUntil;

    /**
     * Creates a pool.
     *
     * @param generator the AI port
     * @param settings  budget and breaker
     * @param clock     clock for the circuit breaker
     */
    public AiMockPool(AiMockGenerator generator, Settings settings, Clock clock) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns one AI-generated sample of the type, asking the model on first use.
     *
     * @param type the class or record
     * @param gaps the fields that need the model (used for the prompt of the first call)
     * @param <T>  the type
     * @return a sample, or empty when none is available (no budget, circuit open, model failure)
     */
    public <T> Optional<T> sample(Class<T> type, List<FieldGap> gaps) {
        Entry e = entries.computeIfAbsent(type, k -> new Entry());
        if (e.samples.isEmpty()) {
            synchronized (e) {
                if (e.samples.isEmpty()) {
                    refill(e, type, gaps);
                }
            }
        }
        List<Object> current = e.samples;
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(type.cast(current.get(Math.floorMod(e.cursor.getAndIncrement(), current.size()))));
    }

    /**
     * Number of model calls made so far.
     *
     * @return calls
     */
    public int calls() {
        return calls.get();
    }

    private <T> void refill(Entry e, Class<T> type, List<FieldGap> gaps) {
        Instant until = openUntil;
        if (until != null && clock.instant().isBefore(until)) {
            return;
        }
        if (calls.get() >= settings.maxCalls()) {
            return;
        }
        calls.incrementAndGet();
        try {
            List<T> batch = generator.generate(type, settings.batchSize(), gaps);
            e.samples = batch.stream().filter(Objects::nonNull).map(o -> (Object) o).toList();
            consecutiveFailures.set(0);
            if (e.samples.isEmpty()) {
                LOG.warn("The AI model returned no usable {} objects", type.getSimpleName());
            }
        } catch (AiMockException | RuntimeException ex) {
            LOG.warn("AI mock generation for {} failed: {}", type.getSimpleName(), ex.getClass().getSimpleName());
            if (consecutiveFailures.incrementAndGet() >= settings.failureThreshold()) {
                openUntil = clock.instant().plus(settings.cooldown());
                consecutiveFailures.set(0);
                LOG.warn("AI mock generation paused for {}", settings.cooldown());
            }
        }
    }
}
