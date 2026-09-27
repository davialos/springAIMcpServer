package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * How the size of an action's result is bounded (LLD-14 §3.3). List-returning actions must be bounded before the
 * call — truncating afterwards does not prevent the heap allocation.
 *
 * @param kind           bounding mechanism
 * @param limitParameter name of the parameter that bounds the result ({@link Kind#PAGEABLE_PARAMETER},
 *                       {@link Kind#SPRING_DATA_LIMIT_PARAMETER}, {@link Kind#LIMIT_PARAMETER}), else {@code null}
 */
public record ResultBounding(Kind kind, @Nullable String limitParameter) {

    /** Bounding mechanisms. */
    public enum Kind {
        /** The action does not return a collection (single value, {@code Optional}, scalar, {@code void}). */
        NOT_A_LIST,
        /** A Spring Data {@code Pageable} parameter bounds the result. */
        PAGEABLE_PARAMETER,
        /** A Spring Data {@code Limit} parameter bounds the result. */
        SPRING_DATA_LIMIT_PARAMETER,
        /** An integral {@code @AiParam}-annotated limit parameter bounds the result. */
        LIMIT_PARAMETER,
        /** The return type is a Spring Data {@code Page}, {@code Slice} or {@code Window}. */
        PAGED_RETURN_TYPE,
        /** A collection is returned without any bound: issue {@code UNBOUNDED_LIST_ACTION}. */
        UNBOUNDED
    }

    /** Validates components. */
    public ResultBounding {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Whether the result size is bounded before the call.
     *
     * @return {@code false} only for {@link Kind#UNBOUNDED}
     */
    public boolean bounded() {
        return kind != Kind.UNBOUNDED;
    }

    /**
     * Whether the action returns a collection-like result.
     *
     * @return {@code true} unless {@link Kind#NOT_A_LIST}
     */
    public boolean returnsList() {
        return kind != Kind.NOT_A_LIST;
    }
}
