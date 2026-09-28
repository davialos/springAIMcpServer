package com.springaimcpservercommon.query.ast;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A dot-separated path to an entity attribute, e.g. {@code customer.name} → segments {@code ["customer", "name"]}.
 *
 * <p>The join path is everything except the last segment; the last segment is the attribute name.
 * A single-segment path (e.g. {@code ["status"]}) has no joins.
 *
 * @param segments path segments, non-empty
 */
public record AttributePath(List<String> segments) {

    /** Validates and copies the segments list. */
    public AttributePath {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("AttributePath must have at least one segment");
        }
        segments.forEach(s -> {
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException("AttributePath segment must not be null or blank");
            }
        });
        segments = List.copyOf(segments);
    }

    /**
     * Creates an attribute path from varargs segments.
     *
     * @param first  first segment (the root attribute or relation name)
     * @param rest   additional segments
     * @return the attribute path
     */
    public static AttributePath of(String first, String... rest) {
        List<String> all = new java.util.ArrayList<>();
        all.add(Objects.requireNonNull(first, "first"));
        all.addAll(Arrays.asList(rest));
        return new AttributePath(all);
    }

    /**
     * Parses a dot-separated path string.
     *
     * @param dotPath dot-separated attribute path, e.g. {@code "customer.name"}
     * @return the parsed path
     */
    public static AttributePath parse(String dotPath) {
        Objects.requireNonNull(dotPath, "dotPath");
        String[] parts = dotPath.split("\\.", -1);
        return new AttributePath(Arrays.asList(parts));
    }

    /**
     * The final segment — the attribute name on the last join (or the root entity).
     *
     * @return last segment
     */
    public String attributeName() {
        return segments.getLast();
    }

    /**
     * All segments except the last — the path of relation names to traverse as JPA joins.
     *
     * @return join path, empty for a direct attribute
     */
    public List<String> joinPath() {
        return segments.subList(0, segments.size() - 1);
    }

    /** The join depth: number of relation hops. A direct attribute has depth 0. */
    public int joinDepth() {
        return segments.size() - 1;
    }

    @Override
    public String toString() {
        return String.join(".", segments);
    }
}
