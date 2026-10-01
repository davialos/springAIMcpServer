package com.springaimcpservercommon.query.adhoc;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.principal.DaiPrincipal;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who asks and what they may reach when a model builds its own criteria query (LLD-05 §12).
 *
 * @param catalog         effective catalog generation the query is checked and run against
 * @param principal       the caller; the query runs as this principal and only sees what they are cleared for
 * @param workspaceId     workspace of the tool binding that offers the query tools
 * @param allowedEntities entities the tool binding allows, by simple name, class name or {@code entity:} reference;
 *                        empty means every entity the catalog exposes to AI
 * @param maxRows         rows per page the tool binding allows (further capped by each entity's own limit)
 */
public record AdhocScope(EffectiveCatalog catalog, DaiPrincipal principal, UUID workspaceId,
                         Set<String> allowedEntities, int maxRows) {

    /** Validates the components and copies the allow-list. */
    public AdhocScope {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(workspaceId, "workspaceId");
        allowedEntities = allowedEntities.stream().map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be >= 1");
        }
    }

    /**
     * Whether a model may query this entity: exposed by the catalog, allowed by the binding and not classified
     * above the caller's clearance.
     *
     * @param entity catalog entity
     * @return {@code true} when visible
     */
    public boolean sees(EffectiveEntity entity) {
        if (!entity.enabled() || !principal.isCleared(entity.classification())) {
            return false;
        }
        return allowedEntities.isEmpty()
                || allowedEntities.contains(entity.name().toLowerCase(Locale.ROOT))
                || allowedEntities.contains(entity.descriptor().javaType().toLowerCase(Locale.ROOT))
                || allowedEntities.contains(entity.ref().toString().toLowerCase(Locale.ROOT));
    }
}
