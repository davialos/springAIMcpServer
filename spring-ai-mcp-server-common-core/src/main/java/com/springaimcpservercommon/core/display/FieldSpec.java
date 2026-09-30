package com.springaimcpservercommon.core.display;

import java.util.Objects;

/**
 * One value to display: where to find it and how to show it.
 *
 * @param path   dot path relative to the block's source ({@code customer.name}, {@code items.0.sku}); {@code $} is
 *               the source value itself (a table of scalars)
 * @param label  column or field label
 * @param format formatting hint for the client
 * @param mask   masking applied before display
 */
public record FieldSpec(String path, String label, DisplayFormat format, DisplayMask mask) {

    /** Validates components. */
    public FieldSpec {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(mask, "mask");
    }
}
