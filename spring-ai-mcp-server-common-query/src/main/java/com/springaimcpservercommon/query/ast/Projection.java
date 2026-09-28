package com.springaimcpservercommon.query.ast;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One column in the SELECT clause of a {@link QueryDefinition} (LLD-05 §2).
 *
 * @param path  attribute path (e.g. {@code customer.name})
 * @param alias optional output key name; when absent the path's string representation is used
 */
public record Projection(AttributePath path, @Nullable String alias) {

    /** Validates the path. */
    public Projection {
        Objects.requireNonNull(path, "path");
    }

    /**
     * Convenience constructor with no alias.
     *
     * @param path attribute path
     */
    public Projection(AttributePath path) {
        this(path, null);
    }

    /**
     * The effective key name used in the result map row.
     *
     * @return alias if set, otherwise the dot-joined path string
     */
    public String outputName() {
        return alias != null ? alias : path.toString();
    }
}
