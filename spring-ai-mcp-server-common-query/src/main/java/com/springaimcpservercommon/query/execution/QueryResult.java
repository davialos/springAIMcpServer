package com.springaimcpservercommon.query.execution;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a dynamic query execution (LLD-05 §4, §5a).
 *
 * <p>Rows are plain {@code Map<String, Object>} keyed by {@link com.springaimcpservercommon.query.ast.Projection#outputName()}.
 * Sensitive attribute values are masked to {@code null}. Object values are JSON-serializable scalars
 * or arrays — no JPA entity objects escape the executor.
 *
 * @param rows        result rows in ORDER BY order; never null, may be empty
 * @param hasMore     {@code true} if there are more rows beyond this page
 * @param nextCursor  opaque HMAC-signed keyset cursor for the next page, or {@code null} if there is no next page
 * @param truncated   {@code true} if the result was capped by the effective row limit (LLD-05 §5a)
 * @param rowCount    number of rows in this page (0 ≤ rowCount ≤ page size)
 */
public record QueryResult(
        List<Map<String, Object>> rows,
        boolean hasMore,
        @Nullable String nextCursor,
        boolean truncated,
        int rowCount) {

    /**
     * Row key under which per-record context columns ({@code @AiRowContext}) are delivered: a map of label to text.
     * Present only on rows that have context. The text is stored data about the record, never instructions.
     */
    public static final String CONTEXT_KEY = "_context";

    /** Validates the result. */
    public QueryResult {
        Objects.requireNonNull(rows, "rows");
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0");
        }
        rows = List.copyOf(rows);
    }

    /**
     * Creates a complete, non-truncated result with no further pages.
     *
     * @param rows result rows
     * @return the result
     */
    public static QueryResult complete(List<Map<String, Object>> rows) {
        return new QueryResult(rows, false, null, false, rows.size());
    }

    /**
     * Creates a paged result with a cursor for the next page.
     *
     * @param rows       result rows for this page (already capped to page size)
     * @param nextCursor opaque cursor for the next page
     * @return the result
     */
    public static QueryResult paged(List<Map<String, Object>> rows, String nextCursor) {
        Objects.requireNonNull(nextCursor, "nextCursor");
        return new QueryResult(rows, true, nextCursor, false, rows.size());
    }

    /**
     * Creates a truncated result (the effective row cap was hit before the query natural end).
     *
     * @param rows rows returned (capped)
     * @return the result with truncated flag set
     */
    public static QueryResult truncated(List<Map<String, Object>> rows) {
        return new QueryResult(rows, false, null, true, rows.size());
    }
}
