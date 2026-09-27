package com.springaimcpservercommon.core.catalog;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link MetadataRegistry}: an {@link AtomicReference} swapped when a newer generation is built (LLD-03 §5).
 * Readers never lock; a publish of an older or equal generation is ignored, so out-of-order rebuilds cannot roll
 * the catalog back.
 */
public final class SwappableMetadataRegistry implements MetadataRegistry {

    private final AtomicReference<EffectiveCatalog> current;

    /**
     * Creates the registry with an initial generation.
     *
     * @param initial initial catalog (e.g. an empty, fail-closed catalog before the first merge)
     */
    public SwappableMetadataRegistry(EffectiveCatalog initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public EffectiveCatalog current() {
        return current.get();
    }

    /**
     * Publishes a new generation if it is newer than the current one.
     *
     * @param next the new catalog
     * @return {@code true} if it became current, {@code false} if it was older or equal and ignored
     */
    public boolean publish(EffectiveCatalog next) {
        Objects.requireNonNull(next, "next");
        EffectiveCatalog prev;
        do {
            prev = current.get();
            if (next.generation() <= prev.generation()) {
                return false;
            }
        } while (!current.compareAndSet(prev, next));
        return true;
    }
}
