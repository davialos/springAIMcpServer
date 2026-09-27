package com.springaimcpservercommon.core.catalog;

import java.util.List;
import java.util.Objects;

/**
 * A path through entity relations, e.g. {@code Customer → orders → Order → lines → OrderLine}.
 *
 * @param from  start entity
 * @param steps relations traversed, in order (at least one)
 * @param to    end entity
 */
public record RelationPath(CatalogElementRef from, List<RelationDescriptor> steps, CatalogElementRef to) {

    /** Validates components and copies collections. */
    public RelationPath {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a relation path has at least one step");
        }
    }

    /**
     * Number of relations traversed.
     *
     * @return path length
     */
    public int length() {
        return steps.size();
    }

    /**
     * Dotted attribute path, e.g. {@code orders.lines}.
     *
     * @return the path expression
     */
    public String expression() {
        return String.join(".", steps.stream().map(RelationDescriptor::name).toList());
    }
}
