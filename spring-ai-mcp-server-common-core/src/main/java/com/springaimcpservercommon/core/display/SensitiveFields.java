package com.springaimcpservercommon.core.display;

import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.lint.SensitiveNames;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keys whose values are never displayed (always fully masked): attributes the effective catalog marks sensitive or
 * disabled ({@code @AiEntityProperty(sensitive = true)} or a policy layer), plus names that look like credentials or
 * regulated identifiers ({@link SensitiveNames}). Matched by key name, case-insensitively, across all entities: a
 * name that is sensitive on one entity is masked everywhere, which errs on the safe side.
 *
 * <p>Immutable and thread-safe.
 */
public final class SensitiveFields {

    private static final SensitiveFields HEURISTIC = new SensitiveFields(Set.of());

    private final Set<String> names;
    private final SensitiveNames heuristic = SensitiveNames.defaults();

    private SensitiveFields(Set<String> names) {
        this.names = Set.copyOf(names);
    }

    /**
     * The name heuristic only (no catalog).
     *
     * @return heuristic-only instance
     */
    public static SensitiveFields heuristic() {
        return HEURISTIC;
    }

    /**
     * Sensitive keys of a catalog plus the name heuristic.
     *
     * @param catalog effective catalog
     * @return the instance
     */
    public static SensitiveFields of(EffectiveCatalog catalog) {
        Set<String> names = new HashSet<>();
        for (EffectiveEntity e : catalog.entities().values()) {
            for (EffectiveAttribute a : e.attributes().values()) {
                if (!a.exposable()) {
                    names.add(a.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new SensitiveFields(names);
    }

    /**
     * Whether a value under this key must be masked.
     *
     * @param key object key (last path segment)
     * @return {@code true} if sensitive
     */
    public boolean isSensitive(String key) {
        return names.contains(key.toLowerCase(Locale.ROOT)) || heuristic.looksSensitive(key);
    }
}
