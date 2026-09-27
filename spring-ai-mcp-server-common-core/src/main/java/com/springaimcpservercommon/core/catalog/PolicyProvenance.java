package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Which layer set which effective value (shown in the dashboard and audited, LLD-03 §2). Values that come from
 * code carry no provenance entry; every change applied by a policy layer adds one, in merge order.
 *
 * @param property property name ({@code enabled}, {@code description}, {@code keywords}, {@code sensitive},
 *                 {@code classification}, {@code maxLimit}, {@code mandatoryFilters}, {@code readOnly})
 * @param layer    layer that applied the value
 * @param source   layer source (file location, overlay revision id, kill-switch id)
 * @param value    safe textual rendering of the new value (descriptions are summarised, never secrets)
 * @param reason   reason given by the layer, if any (mandatory for disables)
 */
public record PolicyProvenance(String property, PolicyLayer layer, String source, String value,
                               @Nullable String reason) {

    /** Validates components. */
    public PolicyProvenance {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(value, "value");
    }
}
