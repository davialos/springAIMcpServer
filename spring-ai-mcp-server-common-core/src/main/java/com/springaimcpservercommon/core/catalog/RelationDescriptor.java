package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A navigable association between two catalog entities (from the JPA metamodel).
 *
 * @param name     attribute name of the association on the owning entity
 * @param kind     cardinality
 * @param target   reference of the target entity ({@code entity:…})
 * @param mappedBy inverse-side attribute name on the target, if bidirectional and this is the inverse side
 * @param optional whether the association may be absent (single-valued associations only)
 */
public record RelationDescriptor(String name, Kind kind, CatalogElementRef target, @Nullable String mappedBy,
                                 boolean optional) {

    /** Cardinality of a relation. */
    public enum Kind {
        /** {@code @OneToOne}. */
        ONE_TO_ONE,
        /** {@code @OneToMany}. */
        ONE_TO_MANY,
        /** {@code @ManyToOne}. */
        MANY_TO_ONE,
        /** {@code @ManyToMany}. */
        MANY_TO_MANY;

        /**
         * Whether the relation navigates to a collection.
         *
         * @return {@code true} for to-many relations
         */
        public boolean toMany() {
            return this == ONE_TO_MANY || this == MANY_TO_MANY;
        }
    }

    /** Validates components. */
    public RelationDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        if (target.kind() != CatalogElementRef.Kind.ENTITY) {
            throw new IllegalArgumentException("relation target must be an entity ref: " + target);
        }
    }
}
