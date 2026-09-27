package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An entity after policy merging.
 *
 * @param ref              {@code entity:} reference
 * @param descriptor       scanned (code) descriptor, kept for diffs
 * @param description      effective description
 * @param keywords         effective keywords
 * @param classification   effective classification (max across layers unless declassified by an OVERLAY)
 * @param maxLimit         effective row cap: min of code, layers and the global cap
 * @param mandatoryFilters effective mandatory filters: union across layers, sorted
 * @param enabled          {@code false} if any layer disabled the entity
 * @param attributes       effective attributes by reference, ordered by name
 * @param provenance       changes applied by policy layers, in merge order
 */
public record EffectiveEntity(CatalogElementRef ref, EntityDescriptor descriptor, String description,
                              List<String> keywords, Classification classification, int maxLimit,
                              List<String> mandatoryFilters, boolean enabled,
                              Map<CatalogElementRef, EffectiveAttribute> attributes,
                              List<PolicyProvenance> provenance) {

    /** Validates components and copies collections (attribute order preserved). */
    public EffectiveEntity {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(classification, "classification");
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("effective classification must be concrete: " + ref);
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException("maxLimit must be >= 1: " + ref);
        }
        keywords = List.copyOf(keywords);
        mandatoryFilters = mandatoryFilters.stream().distinct().sorted().toList();
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        provenance = List.copyOf(provenance);
    }

    /**
     * Logical entity name.
     *
     * @return the name
     */
    public String name() {
        return descriptor.name();
    }

    /**
     * Relations of the entity (relations are code-only).
     *
     * @return relations
     */
    public List<RelationDescriptor> relations() {
        return descriptor.relations();
    }

    /**
     * Finds an effective attribute by name.
     *
     * @param attributeName attribute name
     * @return the attribute, if present
     */
    public Optional<EffectiveAttribute> attribute(String attributeName) {
        return Optional.ofNullable(attributes.get(AttributeDescriptor.refOf(descriptor.javaType(), attributeName)));
    }
}
