package com.springaimcpservercommon.persistence.support;

/**
 * Offset/limit page request for store read queries. Results are returned as a {@link Slice} (the store fetches one
 * extra row to know whether more exist, instead of running a count over partitioned tables).
 *
 * @param offset rows to skip (0–100 000; deep paging over telemetry should narrow the time range instead)
 * @param limit  maximum rows to return (1–{@value #MAX_LIMIT})
 */
public record PageRequest(int offset, int limit) {

    /** Largest page size. */
    public static final int MAX_LIMIT = 500;

    /** Largest offset. */
    public static final int MAX_OFFSET = 100_000;

    /**
     * Validates bounds.
     */
    public PageRequest {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset must be between 0 and " + MAX_OFFSET + ": " + offset);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ": " + limit);
        }
    }

    /**
     * First page.
     *
     * @param limit page size
     * @return the request
     */
    public static PageRequest first(int limit) {
        return new PageRequest(0, limit);
    }

    /**
     * The page after this one.
     *
     * @return the next page request
     */
    public PageRequest next() {
        return new PageRequest(offset + limit, limit);
    }
}
