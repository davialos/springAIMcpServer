package com.springaimcpservercommon.query.ast;

import java.util.Objects;

/**
 * One ORDER BY column in a {@link QueryDefinition} (LLD-05 §2).
 *
 * <p>At least one sort spec is required when keyset pagination is enabled (LLD-05 §5a).
 *
 * @param path       attribute path to sort by
 * @param descending {@code true} for descending order
 */
public record SortSpec(AttributePath path, boolean descending) {

    /** Validates the path. */
    public SortSpec {
        Objects.requireNonNull(path, "path");
    }

    /**
     * Convenience factory for ascending order.
     *
     * @param path attribute path
     * @return ascending sort spec
     */
    public static SortSpec asc(AttributePath path) {
        return new SortSpec(path, false);
    }

    /**
     * Convenience factory for descending order.
     *
     * @param path attribute path
     * @return descending sort spec
     */
    public static SortSpec desc(AttributePath path) {
        return new SortSpec(path, true);
    }
}
