package com.springaimcpservercommon.core.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, effective view of "what the AI may see and do in this host" (LLD-03 §2): the scanned catalog merged
 * with all policy layers. A new generation is built off-thread on every layer change and swapped atomically;
 * readers never lock.
 *
 * @param generation        monotonically increasing generation number
 * @param scanFingerprint   fingerprint of the underlying {@link ScannedCatalog}
 * @param policyFingerprint {@code sha256:} over the canonical content of all layers
 * @param entities          effective entities by reference (ordered by reference)
 * @param operations        effective operations by reference (ordered by reference)
 * @param issues            scan issues followed by policy-resolution issues
 * @param layers            status of each applied policy layer, in merge order
 */
public record EffectiveCatalog(long generation, String scanFingerprint, String policyFingerprint,
                               Map<CatalogElementRef, EffectiveEntity> entities,
                               Map<CatalogElementRef, EffectiveOperation> operations,
                               List<ScanIssue> issues, List<PolicyLayerStatus> layers) {

    /** Validates components and copies collections (order preserved). */
    public EffectiveCatalog {
        Objects.requireNonNull(scanFingerprint, "scanFingerprint");
        Objects.requireNonNull(policyFingerprint, "policyFingerprint");
        entities = Collections.unmodifiableMap(new LinkedHashMap<>(entities));
        operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
        issues = List.copyOf(issues);
        layers = List.copyOf(layers);
    }

    /**
     * Looks up an entity.
     *
     * @param ref entity reference
     * @return the entity, if present (enabled or not)
     */
    public Optional<EffectiveEntity> entity(CatalogElementRef ref) {
        return Optional.ofNullable(entities.get(ref));
    }

    /**
     * Looks up an operation.
     *
     * @param ref operation reference
     * @return the operation, if present (enabled or not)
     */
    public Optional<EffectiveOperation> operation(CatalogElementRef ref) {
        return Optional.ofNullable(operations.get(ref));
    }

    /**
     * Looks up an operation by tool name.
     *
     * @param toolName tool name
     * @return the operation, if present (enabled or not)
     */
    public Optional<EffectiveOperation> operationByToolName(String toolName) {
        return operations.values().stream().filter(o -> o.toolName().equals(toolName)).findFirst();
    }

    /**
     * Operations that are enabled in this generation.
     *
     * @return enabled operations in reference order
     */
    public List<EffectiveOperation> enabledOperations() {
        return operations.values().stream().filter(EffectiveOperation::enabled).toList();
    }

    /**
     * Whether any policy layer was invalid, so the catalog is in fail-closed mode (LLD-03 §4.3).
     *
     * @return {@code true} if some layer is invalid
     */
    public boolean failClosed() {
        return layers.stream().anyMatch(l -> !l.valid());
    }

    /**
     * Simple (cycle-free) relation paths between <em>enabled</em> entities starting at {@code from}, breadth first,
     * up to {@code maxDepth} relations long. Used for join planning and "related entity" hints.
     *
     * @param from     start entity
     * @param maxDepth maximum number of relations per path (≥ 1)
     * @return paths ordered by length, then by expression
     */
    public List<RelationPath> relationPaths(CatalogElementRef from, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        EffectiveEntity start = entities.get(from);
        if (start == null || !start.enabled()) {
            return List.of();
        }
        List<RelationPath> result = new ArrayList<>();
        List<List<RelationDescriptor>> frontier = new ArrayList<>();
        frontier.add(List.of());
        for (int depth = 1; depth <= maxDepth; depth++) {
            List<List<RelationDescriptor>> next = new ArrayList<>();
            for (List<RelationDescriptor> path : frontier) {
                CatalogElementRef tail = path.isEmpty() ? from : path.getLast().target();
                Set<CatalogElementRef> visited = new HashSet<>();
                visited.add(from);
                path.forEach(r -> visited.add(r.target()));
                EffectiveEntity tailEntity = entities.get(tail);
                if (tailEntity == null) {
                    continue;
                }
                for (RelationDescriptor relation : tailEntity.relations()) {
                    EffectiveEntity target = entities.get(relation.target());
                    if (target == null || !target.enabled() || visited.contains(relation.target())) {
                        continue;
                    }
                    List<RelationDescriptor> extended = new ArrayList<>(path);
                    extended.add(relation);
                    result.add(new RelationPath(from, extended, relation.target()));
                    next.add(extended);
                }
            }
            frontier = next;
        }
        result.sort(java.util.Comparator.comparingInt(RelationPath::length).thenComparing(RelationPath::expression));
        return List.copyOf(result);
    }
}
