package com.springaimcpservercommon.query.adhoc;

import com.springaimcpservercommon.query.ast.QueryDefinition;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of checking a model-built criteria query (LLD-05 §12).
 *
 * @param valid      {@code true} when the query may run
 * @param errors     what to fix, each prefixed with where it is in the request (e.g. {@code where.all[1].op})
 * @param warnings   adjustments that were made (a reduced limit, an added sort column)
 * @param explain    the query in readable SQL-like form, for the model and for the person reading the trace
 * @param normalized the request with every default filled in, in the request format
 * @param query      the compiled query definition; {@code null} when invalid
 * @param pageSize   rows per page that will be returned
 * @param cursor     the continuation cursor supplied with the request, if any
 */
public record CriteriaCheck(boolean valid, List<String> errors, List<String> warnings, String explain,
                            Map<String, Object> normalized, @Nullable QueryDefinition query, int pageSize,
                            @Nullable String cursor) {

    /** Copies the lists and the normalized map. */
    public CriteriaCheck {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(explain, "explain");
        normalized = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(normalized));
        if (valid && query == null) {
            throw new IllegalArgumentException("a valid check needs its query");
        }
    }
}
