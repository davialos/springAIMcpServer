package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A scanned catalog entity: a JPA {@code @Entity} annotated with {@code @AiContext} (LLD-02 §2), produced by an
 * {@link EntityCatalogSource}.
 *
 * @param ref              {@code entity:<fully qualified class name>}
 * @param javaType         fully qualified class name
 * @param name             logical name ({@code @AiContext.name} or the simple class name); used in tool results
 *                         instead of physical table names
 * @param description      plain-English description (≤ 1024 chars)
 * @param keywords         domain terms / synonyms
 * @param classification   entity classification (never {@link Classification#INHERIT})
 * @param maxLimit         {@code @AiQueryConstraints.maxLimit} (default 50)
 * @param mandatoryFilters {@code @AiQueryConstraints.mandatoryFilters}
 * @param attributes       exposed attributes (only {@code @AiEntityProperty}-annotated ones), sorted by name
 * @param relations        associations to other catalog entities, sorted by name
 * @param source           id of the source that produced the entity (e.g. the persistence unit name), if any
 */
public record EntityDescriptor(CatalogElementRef ref, String javaType, String name, String description,
                               List<String> keywords, Classification classification, int maxLimit,
                               List<String> mandatoryFilters, List<AttributeDescriptor> attributes,
                               List<RelationDescriptor> relations, @Nullable String source) {

    /** Validates components and copies/sorts collections for deterministic fingerprints. */
    public EntityDescriptor {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(classification, "classification");
        if (ref.kind() != CatalogElementRef.Kind.ENTITY) {
            throw new IllegalArgumentException("entity ref must be of kind ENTITY: " + ref);
        }
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("entity classification must be concrete: " + ref);
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException("maxLimit must be >= 1: " + ref);
        }
        keywords = List.copyOf(keywords);
        mandatoryFilters = mandatoryFilters.stream().distinct().sorted().toList();
        attributes = attributes.stream().sorted(Comparator.comparing(AttributeDescriptor::name)).toList();
        relations = relations.stream().sorted(Comparator.comparing(RelationDescriptor::name)).toList();
    }

    /**
     * Finds an attribute by name.
     *
     * @param attributeName attribute name
     * @return the attribute or {@code null}
     */
    public @Nullable AttributeDescriptor attribute(String attributeName) {
        for (AttributeDescriptor a : attributes) {
            if (a.name().equals(attributeName)) {
                return a;
            }
        }
        return null;
    }
}
