package com.springaimcpservercommon.query.execution;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.RowPolicy;

import java.util.List;
import java.util.Map;

/**
 * SPI: executes a published dynamic query for a given caller (LLD-05 §4, ADR-0004).
 *
 * <p>The default implementation ({@link com.springaimcpservercommon.query.criteria.CriteriaQueryExecutor})
 * compiles the AST to JPA Criteria and runs it in a read-only transaction against the host's
 * {@code EntityManagerFactory}. Hosts may provide an alternative (e.g. read-replica) via
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Callers must ensure:
 * <ul>
 *   <li>The query has been validated by {@link com.springaimcpservercommon.query.validation.QueryValidator}
 *       before calling this method.</li>
 *   <li>Row policies applicable to the caller and entity have been resolved and passed in.</li>
 * </ul>
 *
 * <p>The executor enforces a per-node bulkhead semaphore; callers that exceed the concurrency
 * cap receive a {@link QueryBulkheadException}.
 */
public interface QueryExecutor {

    /**
     * Executes the query and returns a page of results.
     *
     * @param query       validated and published query definition
     * @param principal   calling principal (used for row policy binding and clearance masking)
     * @param params      caller-supplied parameter values (keyed by {@link com.springaimcpservercommon.query.ast.QueryParam#name()})
     * @param rowPolicies applicable row policies to AND on the WHERE clause
     * @param catalog     current effective catalog generation
     * @param requestedSize caller-requested page size (0 or negative ⇒ use {@code query.page().defaultSize()})
     * @return the result page
     * @throws QueryBulkheadException   if the per-node concurrency cap is exceeded
     * @throws jakarta.persistence.QueryTimeoutException if the query exceeds the configured timeout
     */
    QueryResult execute(
            QueryDefinition query,
            DaiPrincipal principal,
            Map<String, Object> params,
            List<RowPolicy> rowPolicies,
            EffectiveCatalog catalog,
            int requestedSize);
}
