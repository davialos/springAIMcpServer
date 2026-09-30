package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;
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

    /**
     * A page of catalog rows.
     *
     * @param items   rows
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     * @param <T>     row type
     */
    public record PageView<T>(List<T> items, int limit, int offset, boolean hasMore) {}

    static final int MAX_QUERY = 100;

    private final MetadataRegistry metadataRegistry;
    private final AdminApi api;

    CatalogAdminController(MetadataRegistry metadataRegistry, AdminApi api) {
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Catalog summary (generation, fingerprint, counts). Allowed in every environment for callers with
     * {@code catalog:read}; the detail endpoints below are introspection and are off in production (LLD-12 §2.2).
     *
     * @param request current request
     * @return 200 with the summary
     */
    @GetMapping
    public ResponseEntity<?> catalogInfo(HttpServletRequest request) {
        var gate = api.gate(request, Permission.CATALOG_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        EffectiveCatalog c = metadataRegistry.current();
        return ResponseEntity.ok(new CatalogInfo(
                c.generation(), c.scanFingerprint(), c.entities().size(), c.operations().size()));
    }

    /**
     * Entities of the effective catalog, searchable and paged.
     *
     * @param q       optional case-insensitive filter on ref, name and description (max 100 characters)
     * @param limit   page size (1..200, default 50)
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page; 403 {@code capability-disabled} when introspection is off
     */
    @GetMapping("/entities")
    public ResponseEntity<?> entities(@RequestParam(required = false) @Nullable String q,
                                      @RequestParam(required = false) @Nullable Integer limit,
                                      @RequestParam(required = false) @Nullable Integer offset,
                                      HttpServletRequest request) {
        var gate = api.gate(request, Permission.CATALOG_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.INTROSPECTION, request);
        if (disabled != null) {
            return disabled;
        }
        String needle = needle(q);
        var page = AdminApi.page(limit, offset);
        List<EntitySummary> all = metadataRegistry.current().entities().values().stream()
                .filter(e -> needle == null || matches(needle, e.ref().toString(), e.name(), e.description()))
                .map(e -> new EntitySummary(
                        e.ref().toString(),
                        e.name(),
                        e.description(),
                        e.enabled(),
                        e.maxLimit(),
                        e.classification().name()))
                .toList();
        return ResponseEntity.ok(slice(all, page.offset(), page.limit()));
    }

    /**
     * Operations of the effective catalog, searchable and paged.
     *
     * @param q       optional case-insensitive filter on ref, tool name and description (max 100 characters)
     * @param limit   page size (1..200, default 50)
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page; 403 {@code capability-disabled} when introspection is off
     */
    @GetMapping("/operations")
    public ResponseEntity<?> operations(@RequestParam(required = false) @Nullable String q,
                                        @RequestParam(required = false) @Nullable Integer limit,
                                        @RequestParam(required = false) @Nullable Integer offset,
                                        HttpServletRequest request) {
        var gate = api.gate(request, Permission.CATALOG_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.INTROSPECTION, request);
        if (disabled != null) {
            return disabled;
        }
        String needle = needle(q);
        var page = AdminApi.page(limit, offset);
        List<OperationSummary> all = metadataRegistry.current().operations().values().stream()
                .filter(o -> needle == null || matches(needle, o.ref().toString(), o.toolName(), o.description()))
                .map(o -> new OperationSummary(
                        o.ref().toString(),
                        o.toolName(),
                        o.description(),
                        o.enabled(),
                        o.readOnly(),
                        o.classification().name()))
                .toList();
        return ResponseEntity.ok(slice(all, page.offset(), page.limit()));
    }

    private static @Nullable String needle(@Nullable String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String v = q.strip();
        if (v.length() > MAX_QUERY) {
            throw new IllegalArgumentException("q must be at most " + MAX_QUERY + " characters");
        }
        return v.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String needle, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static <T> PageView<T> slice(List<T> all, int offset, int limit) {
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return new PageView<>(all.subList(from, to), limit, offset, to < all.size());
    }
}
