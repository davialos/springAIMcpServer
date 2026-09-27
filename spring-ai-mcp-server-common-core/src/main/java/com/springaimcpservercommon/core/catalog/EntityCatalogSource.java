package com.springaimcpservercommon.core.catalog;

import java.util.List;
import java.util.function.Consumer;

/**
 * SPI producing catalog entities (LLD-02 §3.2). The query module implements it from
 * {@code EntityManagerFactory.getMetamodel()} — one source per entity manager factory — applying the
 * attribute visibility rules of LLD-02 §4 ({@code @AiEntityProperty} only, {@code sensitive}/{@code @JsonIgnore}/
 * {@code @Transient} handling, the sensitive-name heuristic in
 * {@link com.springaimcpservercommon.core.lint.SensitiveNames}, text lint in
 * {@link com.springaimcpservercommon.core.lint.TextLint}).
 *
 * <p>Implementations must not throw for a single bad entity: skip it and report a {@link ScanIssue}
 * ({@link ScanIssueCode#SCAN_FAILED}) through the sink (fail the feature, not the host). An exception escaping
 * {@link #scanEntities} is caught by the caller, recorded, and the source contributes no entities.
 */
public interface EntityCatalogSource {

    /**
     * Stable id of the source, e.g. the persistence-unit name; recorded as {@link EntityDescriptor#source()}.
     *
     * @return the source id
     */
    String sourceId();

    /**
     * Scans the entities once. Called exactly once per context, after all singletons exist.
     *
     * @param issues sink for issues found while scanning
     * @return entity descriptors of {@code @AiContext}-annotated entities, in any order
     */
    List<EntityDescriptor> scanEntities(Consumer<ScanIssue> issues);
}
