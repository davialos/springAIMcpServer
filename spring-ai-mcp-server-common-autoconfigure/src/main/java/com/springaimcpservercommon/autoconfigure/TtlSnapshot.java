package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value reloaded from the store at most once per TTL and shared by all callers on the node (node-local cache,
 * ADR-0021). If a reload fails the last good value keeps being served and the failure is logged; only when nothing
 * was ever loaded does the failure reach the caller. Used for small, hot, read-mostly sets such as active kill
 * switches and role mappings.
 *
 * @param <T> the cached value type
 */
@NullMarked
final class TtlSnapshot<T> {

    private static final Logger LOG = LoggerFactory.getLogger(TtlSnapshot.class);

    private final Supplier<T> loader;
    private final Duration ttl;
    private final Clock clock;
    private final String name;
    private volatile @Nullable T value;
    private volatile Instant expiresAt = Instant.MIN;

    TtlSnapshot(String name, Supplier<T> loader, Duration ttl, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Forces the next {@link #get()} to reload; the last good value still serves if that reload fails. */
    void invalidate() {
        expiresAt = Instant.MIN;
    }

    /**
     * Returns the cached value, reloading it when the TTL has passed.
     *
     * @return the current value
     */
    T get() {
        Instant now = clock.instant();
        T current = value;
        if (current != null && expiresAt.isAfter(now)) {
            return current;
        }
        synchronized (this) {
            current = value;
            if (current != null && expiresAt.isAfter(clock.instant())) {
                return current;
            }
            try {
                T loaded = loader.get();
                value = loaded;
                expiresAt = clock.instant().plus(ttl);
                return loaded;
            } catch (RuntimeException e) {
                if (current == null) {
                    throw e;
                }
                LOG.warn("Reloading {} failed ({}); serving the previous value", name, e.getClass().getSimpleName());
                expiresAt = clock.instant().plus(ttl);
                return current;
            }
        }
    }
}
