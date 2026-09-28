package com.springaimcpservercommon.query.execution;

/**
 * Thrown when the per-node query concurrency cap is exceeded (LLD-05 §7, §9).
 *
 * <p>Callers should translate this to HTTP 503 with a {@code Retry-After} header.
 */
public final class QueryBulkheadException extends RuntimeException {

    private final int maxConcurrent;

    /**
     * Creates the exception.
     *
     * @param maxConcurrent the concurrency cap that was exceeded
     */
    public QueryBulkheadException(int maxConcurrent) {
        super("Query concurrency cap exceeded (max " + maxConcurrent + " concurrent queries per node)");
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * Returns the concurrency cap that was exceeded.
     *
     * @return max concurrent queries per node
     */
    public int maxConcurrent() {
        return maxConcurrent;
    }
}
