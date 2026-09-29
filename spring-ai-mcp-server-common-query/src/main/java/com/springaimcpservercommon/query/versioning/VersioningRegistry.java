package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.versioning.RecordVersions;
import com.springaimcpservercommon.core.versioning.VersionLookup;
import com.springaimcpservercommon.core.versioning.VersioningAdapter;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link RecordVersions} over a list of {@link VersioningAdapter}s, in order (LLD-11 §5): host-registered adapters first
 * (Envers, history tables), then JPA {@code @Version}, then the row-hash fallback. The first adapter that supports the
 * entity answers; if it reports the id unreadable the next one is tried. A failing adapter is logged by class and skipped,
 * so a broken host adapter degrades to the next mechanism instead of failing a proposal or an apply outright (the callers
 * decide what a missing answer means).
 */
@NullMarked
public final class VersioningRegistry implements RecordVersions {

    private static final Logger LOG = LoggerFactory.getLogger(VersioningRegistry.class);

    private final List<VersioningAdapter> adapters;
    private final JpaExposedValues exposedValues;

    /**
     * Creates the registry.
     *
     * @param adapters      adapters in priority order
     * @param exposedValues reader of exposed attribute values
     */
    VersioningRegistry(List<VersioningAdapter> adapters, JpaExposedValues exposedValues) {
        this.adapters = List.copyOf(adapters);
        this.exposedValues = Objects.requireNonNull(exposedValues, "exposedValues");
    }

    /**
     * The registry for a host's JPA unit: host adapters, then {@link JpaVersionAdapter}, then {@link RowHashAdapter}.
     *
     * @param entityManagerFactory the host's entity manager factory
     * @param catalog              the live catalog
     * @param hostAdapters         adapters the host registered, consulted first
     * @return the registry
     */
    public static VersioningRegistry forJpa(EntityManagerFactory entityManagerFactory,
                                            Supplier<EffectiveCatalog> catalog, List<VersioningAdapter> hostAdapters) {
        List<VersioningAdapter> all = new ArrayList<>(hostAdapters);
        all.add(new JpaVersionAdapter(entityManagerFactory));
        all.add(new RowHashAdapter(entityManagerFactory, catalog));
        return new VersioningRegistry(all, new JpaExposedValues(entityManagerFactory, catalog));
    }

    @Override
    public VersionLookup current(CatalogElementRef entity, String entityId) {
        for (VersioningAdapter adapter : adapters) {
            try {
                if (!adapter.supports(entity)) {
                    continue;
                }
                VersionLookup lookup = adapter.lookup(entity, entityId);
                if (!(lookup instanceof VersionLookup.Unsupported)) {
                    return lookup;
                }
            } catch (RuntimeException e) {
                LOG.warn("Versioning adapter {} failed ({}); trying the next one", adapter.id(),
                        e.getClass().getSimpleName());
            }
        }
        return new VersionLookup.Unsupported();
    }

    @Override
    public Optional<Map<String, Object>> exposedValues(CatalogElementRef entity, String entityId,
                                                       Classification clearance) {
        try {
            return exposedValues.read(entity, entityId, clearance);
        } catch (RuntimeException e) {
            LOG.warn("Reading exposed values failed ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
