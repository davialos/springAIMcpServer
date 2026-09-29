package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Objects;

/**
 * Read-only view of the live {@link EffectiveCatalog} for the admin control plane (LLD-08 §2.1).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /dynamic-ai/admin/api/v1/catalog} — catalog metadata (generation, counts)</li>
 *   <li>{@code GET /dynamic-ai/admin/api/v1/catalog/entities} — effective entity list</li>
 *   <li>{@code GET /dynamic-ai/admin/api/v1/catalog/operations} — effective operation list</li>
 * </ul>
 *
 * <p>Not a {@code @Component} — registered as a bean by {@link DaiAdminAutoConfiguration}.
 * Spring MVC detects it via the class-level {@link RequestMapping}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/catalog")
public final class CatalogAdminController {

    /**
     * Summary view of an effective entity.
     *
     * @param ref            catalog element reference string (e.g. {@code entity:com.example.Order})
     * @param name           display name from {@code @AiContext}
     * @param description    effective description after policy merge
     * @param enabled        {@code false} if any layer disabled this entity
     * @param maxLimit       effective row cap
     * @param classification effective classification label
     */
    public record EntitySummary(
            String ref,
            String name,
            String description,
            boolean enabled,
            int maxLimit,
            String classification) {}

    /**
     * Summary view of an effective operation.
     *
     * @param ref            catalog element reference string (e.g. {@code op:...})
     * @param toolName       AI tool name
     * @param description    effective description after policy merge
     * @param enabled        {@code false} if any layer disabled this operation
     * @param readOnly       effective read-only flag
     * @param classification effective classification label
     */
    public record OperationSummary(
            String ref,
            String toolName,
            String description,
            boolean enabled,
            boolean readOnly,
            String classification) {}

    /**
     * Catalog metadata summary.
     *
     * @param generation      monotonically increasing generation number
     * @param scanFingerprint fingerprint of the underlying scanned catalog
     * @param entityCount     total number of entities (enabled + disabled)
     * @param operationCount  total number of operations (enabled + disabled)
     */
    public record CatalogInfo(
            long generation,
            String scanFingerprint,
            int entityCount,
            int operationCount) {}

    private final MetadataRegistry metadataRegistry;

    /**
     * @param metadataRegistry live effective catalog
     */
    public CatalogAdminController(MetadataRegistry metadataRegistry) {
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
    }

    /**
     * Returns catalog generation metadata and counts.
     *
     * @return catalog info
     */
    @GetMapping
    public ResponseEntity<CatalogInfo> catalogInfo() {
        EffectiveCatalog c = metadataRegistry.current();
        return ResponseEntity.ok(new CatalogInfo(
                c.generation(), c.scanFingerprint(), c.entities().size(), c.operations().size()));
    }

    /**
     * Returns all effective entities ordered by reference.
     *
     * @return entity summaries
     */
    @GetMapping("/entities")
    public ResponseEntity<List<EntitySummary>> entities() {
        List<EntitySummary> result = metadataRegistry.current().entities().values().stream()
                .map(e -> new EntitySummary(
                        e.ref().toString(),
                        e.name(),
                        e.description(),
                        e.enabled(),
                        e.maxLimit(),
                        e.classification().name()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Returns all effective operations ordered by reference.
     *
     * @return operation summaries
     */
    @GetMapping("/operations")
    public ResponseEntity<List<OperationSummary>> operations() {
        List<OperationSummary> result = metadataRegistry.current().operations().values().stream()
                .map(o -> new OperationSummary(
                        o.ref().toString(),
                        o.toolName(),
                        o.description(),
                        o.enabled(),
                        o.readOnly(),
                        o.classification().name()))
                .toList();
        return ResponseEntity.ok(result);
    }
}
