package com.springaimcpservercommon.persistence.support;

import java.util.List;
import java.util.Objects;

/**
 * One page of results.
 *
 * @param items   the rows of this page (immutable)
 * @param page    the request that produced it
 * @param hasMore whether at least one more row exists after this page
 * @param <T>     row type
 */
public record Slice<T>(List<T> items, PageRequest page, boolean hasMore) {

    /**
     * Copies the items.
     */
    public Slice {
        items = List.copyOf(items);
        Objects.requireNonNull(page, "page");
    }

    /**
     * Builds a slice from a result fetched with {@code limit + 1} rows.
     *
     * @param fetched rows fetched with {@code page.limit() + 1} as maximum
     * @param page    the page request
     * @param <T>     row type
     * @return the slice
     */
    public static <T> Slice<T> fromOverfetch(List<T> fetched, PageRequest page) {
        boolean more = fetched.size() > page.limit();
        return new Slice<>(more ? fetched.subList(0, page.limit()) : fetched, page, more);
    }
}
