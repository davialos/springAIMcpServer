package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable result of the startup scan (LLD-02 §3.3): what the host's <em>code</em> exposes (layer L0), before any
 * policy layer is applied.
 *
 * <p>Maps are ordered by the textual element reference, so iteration order — and therefore the canonical form —
 * is deterministic. {@link #scanFingerprint()} is {@code sha256:} over the canonical JSON of all descriptors
 * (entities, operations, contexts; not issues, not the timestamp); published resources pin it for drift detection
 * (LLD-03 §6).
 *
 * @param scanFingerprint {@code sha256:<hex>} over the canonical descriptor list
 * @param scannedAt       when the scan ran
 * @param hostVersion     host application version, if known
 * @param entities        entities by reference
 * @param operations      operations by reference
 * @param contexts        descriptive contexts by reference
 * @param issues          scan issues, deterministically ordered
 */
public record ScannedCatalog(String scanFingerprint, Instant scannedAt, @Nullable String hostVersion,
                             Map<CatalogElementRef, EntityDescriptor> entities,
                             Map<CatalogElementRef, OperationDescriptor> operations,
                             Map<CatalogElementRef, ContextDescriptor> contexts,
                             List<ScanIssue> issues) {

    /**
     * Validates, orders and copies components and verifies the fingerprint.
     */
    public ScannedCatalog {
        Objects.requireNonNull(scanFingerprint, "scanFingerprint");
        Objects.requireNonNull(scannedAt, "scannedAt");
        entities = sorted(entities, EntityDescriptor::ref);
        operations = sorted(operations, OperationDescriptor::ref);
        contexts = sorted(contexts, ContextDescriptor::ref);
        issues = issues.stream().sorted(ScanIssue.ORDER).toList();
        String expected = fingerprint(entities, operations, contexts);
        if (!expected.equals(scanFingerprint)) {
            throw new IllegalArgumentException("scanFingerprint does not match the descriptors");
        }
    }

    /**
     * Builds a catalog and computes its fingerprint.
     *
     * @param scannedAt   scan time
     * @param hostVersion host version, if known
     * @param entities    entity descriptors (unique refs)
     * @param operations  operation descriptors (unique refs)
     * @param contexts    context descriptors (unique refs)
     * @param issues      issues
     * @return the catalog
     * @throws IllegalArgumentException on duplicate references
     */
    public static ScannedCatalog of(Instant scannedAt, @Nullable String hostVersion,
                                    Collection<EntityDescriptor> entities,
                                    Collection<OperationDescriptor> operations,
                                    Collection<ContextDescriptor> contexts,
                                    List<ScanIssue> issues) {
        Map<CatalogElementRef, EntityDescriptor> e = index(entities, EntityDescriptor::ref);
        Map<CatalogElementRef, OperationDescriptor> o = index(operations, OperationDescriptor::ref);
        Map<CatalogElementRef, ContextDescriptor> c = index(contexts, ContextDescriptor::ref);
        return new ScannedCatalog(fingerprint(sorted(e, EntityDescriptor::ref), sorted(o, OperationDescriptor::ref),
                sorted(c, ContextDescriptor::ref)), scannedAt, hostVersion, e, o, c, issues);
    }

    /**
     * An empty catalog (no annotated elements), e.g. when the scan is disabled.
     *
     * @param scannedAt scan time
     * @return an empty catalog
     */
    public static ScannedCatalog empty(Instant scannedAt) {
        return of(scannedAt, null, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Canonical JSON of all descriptors — the content the fingerprint is computed from, also used by the catalog
     * export / golden-file test (LLD-02 §5).
     *
     * @return canonical JSON array
     */
    public String canonicalJson() {
        return CanonicalJson.write(CatalogCanonicalForm.all(this));
    }

    /**
     * Operations with the given tool name (normally at most one; duplicates are excluded by the scanner).
     *
     * @param toolName tool name
     * @return matching operations
     */
    public List<OperationDescriptor> operationsByToolName(String toolName) {
        return operations.values().stream().filter(o -> o.toolName().equals(toolName)).toList();
    }

    private static String fingerprint(Map<CatalogElementRef, EntityDescriptor> entities,
                                      Map<CatalogElementRef, OperationDescriptor> operations,
                                      Map<CatalogElementRef, ContextDescriptor> contexts) {
        java.util.ArrayList<Object> all = new java.util.ArrayList<>();
        entities.values().forEach(e -> all.add(CatalogCanonicalForm.entity(e)));
        operations.values().forEach(op -> all.add(CatalogCanonicalForm.operation(op)));
        contexts.values().forEach(ctx -> all.add(CatalogCanonicalForm.context(ctx)));
        return Sha256.of(CanonicalJson.write(all));
    }

    private static <V> Map<CatalogElementRef, V> index(Collection<V> values, Function<V, CatalogElementRef> key) {
        Map<CatalogElementRef, V> map = new LinkedHashMap<>();
        for (V v : values) {
            if (map.putIfAbsent(key.apply(v), v) != null) {
                throw new IllegalArgumentException("duplicate catalog element: " + key.apply(v));
            }
        }
        return map;
    }

    private static <V> Map<CatalogElementRef, V> sorted(Map<CatalogElementRef, V> map,
                                                        Function<V, CatalogElementRef> key) {
        Map<CatalogElementRef, V> out = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
                    V value = Objects.requireNonNull(entry.getValue(), "value");
                    if (!key.apply(value).equals(entry.getKey())) {
                        throw new IllegalArgumentException("map key does not match element ref: " + entry.getKey());
                    }
                    out.put(entry.getKey(), value);
                });
        return Collections.unmodifiableMap(out);
    }
}
