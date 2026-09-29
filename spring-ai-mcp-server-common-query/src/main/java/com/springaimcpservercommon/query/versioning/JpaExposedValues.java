package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads the exposed attribute values of one record for a review diff: enabled, not sensitive, classified at or below the
 * caller's clearance (the entity's own classification included), basic attributes only. It does <b>not</b> apply the
 * host's row-level visibility, so what it returns may be a row the caller could not otherwise read; callers therefore
 * make it opt-in ({@code dynamic.ai.agent.write.capture-before-values}).
 */
@NullMarked
final class JpaExposedValues {

    private final JpaRecords records;
    private final Supplier<EffectiveCatalog> catalog;

    JpaExposedValues(EntityManagerFactory entityManagerFactory, Supplier<EffectiveCatalog> catalog) {
        this.records = new JpaRecords(Objects.requireNonNull(entityManagerFactory, "entityManagerFactory"));
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    Optional<Map<String, Object>> read(CatalogElementRef entity, String entityId, Classification clearance) {
        EffectiveEntity effective = catalog.get().entity(entity).orElse(null);
        JpaRecords.Meta meta = records.meta(entity).orElse(null);
        if (effective == null || meta == null || !effective.enabled()
                || effective.classification().compareTo(clearance) > 0) {
            return Optional.empty();
        }
        List<String> names = effective.attributes().values().stream()
                .filter(EffectiveAttribute::enabled)
                .filter(a -> !a.sensitive() && !a.descriptor().sensitive())
                .filter(a -> a.classification().compareTo(clearance) <= 0)
                .map(a -> a.descriptor().name())
                .filter(name -> JpaRecords.isBasic(meta, name))
                .sorted()
                .toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        if (!(records.read(meta, entityId, names) instanceof JpaRecords.Row.Found found)) {
            return Optional.empty();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            Object normalised = RowHashAdapter.normalise(found.values()[i]);
            if (normalised != null) {
                values.put(names.get(i), normalised);
            }
        }
        return Optional.of(values);
    }
}
