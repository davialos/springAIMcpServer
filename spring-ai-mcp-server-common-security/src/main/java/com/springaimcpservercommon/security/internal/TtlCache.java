package com.springaimcpservercommon.security.internal;

import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small bounded cache with per-entry expiry and least-recently-used eviction.
 *
 * <p>Deliberately dependency-free (the library must not add Caffeine to the host's classpath for this). Every entry
 * lives at most {@code maxTtl}; a caller may shorten an entry's life (e.g. to a token's expiry) but never extend it.
 * Operations take a short lock; values are computed outside the lock by callers, so a miss storm may compute a value
 * twice, which is harmless for the idempotent lookups cached here.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final int maxEntries;
    private final Duration maxTtl;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, Entry<V>> entries;

    /**
     * Creates a cache.
     *
     * @param maxEntries upper bound of entries (least recently used are evicted), at least 1
     * @param maxTtl     maximum lifetime of an entry, positive
     * @param clock      time source
     */
    public TtlCache(int maxEntries, Duration maxTtl, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        if (maxTtl.isNegative() || maxTtl.isZero()) {
            throw new IllegalArgumentException("maxTtl must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxTtl = maxTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new LinkedHashMap<>(Math.min(maxEntries, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > TtlCache.this.maxEntries;
            }
        };
    }

    /**
     * Returns a live value.
     *
     * @param key the key
     * @return the value if present and not expired
     */
    public Optional<V> get(K key) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (!entry.expiresAt().isAfter(now)) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores a value for the maximum TTL.
     *
     * @param key   the key
     * @param value the value
     */
    public void put(K key, V value) {
        put(key, value, null);
    }

    /**
     * Stores a value that expires at the earlier of {@code notAfter} and now + max TTL.
     *
     * @param key      the key
     * @param value    the value
     * @param notAfter optional earlier expiry, e.g. a token's {@code exp}
     */
    public void put(K key, V value, @Nullable Instant notAfter) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(maxTtl);
        if (notAfter != null && notAfter.isBefore(expiresAt)) {
            expiresAt = notAfter;
        }
        if (!expiresAt.isAfter(now)) {
            return;
        }
        lock.lock();
        try {
            entries.put(key, new Entry<>(value, expiresAt));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes one entry.
     *
     * @param key the key
     */
    public void invalidate(K key) {
        lock.lock();
        try {
            entries.remove(key);
        } finally {
            lock.unlock();
        }
    }

    /** Removes every entry. */
    public void invalidateAll() {
        lock.lock();
        try {
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of entries, including expired ones not yet purged.
     *
     * @return entry count
     */
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }
}
