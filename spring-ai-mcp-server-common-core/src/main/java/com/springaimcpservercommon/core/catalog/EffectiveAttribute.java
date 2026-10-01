package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;

import java.util.List;
import java.util.Objects;

/**
 * An attribute after policy merging.
 *
 * @param ref            {@code attr:} reference
 * @param descriptor     scanned (code) descriptor, kept for diffs
 * @param meaning        effective meaning (latest non-empty layer wins)
 * @param sensitive      effective sensitivity (any layer may raise; only an OVERLAY with {@code declassify} lowers)
 * @param classification effective, concrete classification ({@code INHERIT} resolved against the effective entity)
 * @param enabled        {@code false} if any layer disabled the attribute
 * @param provenance     changes applied by policy layers, in merge order
 */
public record EffectiveAttribute(CatalogElementRef ref, AttributeDescriptor descriptor, String meaning,
                                 boolean sensitive, Classification classification, boolean enabled,
                                 List<PolicyProvenance> provenance) {

    /** Validates components and copies collections. */
    public EffectiveAttribute {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(meaning, "meaning");
        Objects.requireNonNull(classification, "classification");
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("effective classification must be concrete: " + ref);
        }
        provenance = List.copyOf(provenance);
    }

    /**
     * Attribute name.
     *
     * @return the name
     */
    public String name() {
        return descriptor.name();
    }

    /**
     * Whether the column is per-record context that is delivered with every row (see {@code @AiRowContext}) and may
     * be, in this catalog generation: enabled and not sensitive.
     *
     * @return {@code true} if row context is to be delivered
     */
    public boolean rowContext() {
        return descriptor.rowContext() && exposable();
    }

    /**
     * Whether the attribute may be shown to a model: enabled and not sensitive.
     *
     * @return {@code true} if exposable
     */
    public boolean exposable() {
        return enabled && !sensitive;
    }
}
