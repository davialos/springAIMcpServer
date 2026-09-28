package com.springaimcpservercommon.query.criteria;

/**
 * Internal hint keys used to pass metadata between the {@link CriteriaCompiler} and
 * {@link CriteriaQueryExecutor} via {@link jakarta.persistence.TypedQuery#setHint(String, Object)}.
 */
final class CompiledQueryHints {

    /**
     * Hint key carrying the ordered list of projection output names ({@code List<String>}),
     * used by the executor to map tuple positions to result map keys.
     */
    static final String OUTPUT_NAMES = "com.springaimcpservercommon.query.outputNames";

    private CompiledQueryHints() {}
}
