package com.springaimcpservercommon.query.ast;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.principal.FrameworkRole;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A row-level security policy for a catalog entity (LLD-05 §5).
 *
 * <p>The predicate is AND-ed to every dynamic query whose root matches {@link #entity()} and whose
 * caller is covered by this policy (i.e. the caller holds a role in {@link #appliesTo()}, or
 * {@link #appliesTo()} is empty, and does not hold a role in {@link #exemptRoles()}).
 *
 * <p>Predicates may use {@link Operand.PrincipalAttr} to bind values from the calling
 * {@link com.springaimcpservercommon.core.principal.DaiPrincipal}. If the referenced attribute
 * is absent the predicate evaluates to {@code FALSE} (fail-closed).
 *
 * <p>Example: {@code Order.tenantId EQ principal.tenantId} ensures a caller only sees orders
 * belonging to their tenant.
 *
 * @param id           unique policy id (UUIDv7)
 * @param entity       {@code entity:} reference this policy covers
 * @param predicate    filter to AND on every matching query
 * @param appliesTo    roles this policy applies to; empty means all principals
 * @param exemptRoles  roles that bypass this policy (e.g. admins)
 */
public record RowPolicy(
        UUID id,
        CatalogElementRef entity,
        FilterNode predicate,
        Set<FrameworkRole> appliesTo,
        Set<FrameworkRole> exemptRoles) {

    /** Validates and defensively copies the collection fields. */
    public RowPolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entity, "entity");
        if (entity.kind() != CatalogElementRef.Kind.ENTITY) {
            throw new IllegalArgumentException("entity ref must have kind ENTITY: " + entity);
        }
        Objects.requireNonNull(predicate, "predicate");
        appliesTo = Set.copyOf(appliesTo);
        exemptRoles = Set.copyOf(exemptRoles);
    }

    /**
     * Whether this policy applies to the given principal role set.
     *
     * @param principalRoles the global framework roles the principal holds
     * @return {@code true} if the policy's predicate must be applied
     */
    public boolean appliesTo(Set<FrameworkRole> principalRoles) {
        if (exemptRoles.stream().anyMatch(principalRoles::contains)) {
            return false;
        }
        return appliesTo.isEmpty() || appliesTo.stream().anyMatch(principalRoles::contains);
    }
}
