package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

/**
 * A JSON array.
 *
 * @param items    element schema
 * @param minItems minimum element count
 * @param maxItems maximum element count
 */
public record ArraySchema(Schema items, @Nullable Integer minItems, @Nullable Integer maxItems) implements Schema {

    /**
     * Copy with size bounds.
     *
     * @param min minimum
     * @param max maximum
     * @return the copy
     */
    public ArraySchema withBounds(@Nullable Integer min, @Nullable Integer max) {
        return new ArraySchema(items, min, max);
    }
}
