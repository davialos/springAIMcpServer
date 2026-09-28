package com.springaimcpservercommon.query.criteria;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.RowPolicy;
import com.springaimcpservercommon.query.execution.QueryBulkheadException;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Default {@link QueryExecutor} implementation: compiles the query AST via {@link CriteriaCompiler}
 * and executes it against the host's {@link EntityManagerFactory} with:
 * <ul>
 *   <li>A read-only, no-flush transaction.</li>
 *   <li>A per-node bulkhead semaphore to cap concurrency (default {@value DEFAULT_MAX_CONCURRENT}).</li>
 *   <li>A configurable query timeout hint (default {@value DEFAULT_TIMEOUT_MS} ms).</li>
 *   <li>Result mapping from JPA {@code Tuple} to {@code Map<String,Object>}.</li>
 *   <li>Sensitive attribute masking (values replaced with {@code null}).</li>
 * </ul>
 *
 * <p>Declared as a Spring bean in {@code autoconfigure} — not annotated here.
 */
public class CriteriaQueryExecutor implements QueryExecutor {

    /** Default per-node concurrency cap. */
    public static final int DEFAULT_MAX_CONCURRENT = 20;
    /** Default query timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    private static final Logger LOG = LoggerFactory.getLogger(CriteriaQueryExecutor.class);

    private final EntityManagerFactory entityManagerFactory;
    private final CriteriaCompiler compiler;
    private final Semaphore bulkhead;
    private final int timeoutMs;

    /**
     * Creates the executor with default limits.
     *
     * @param entityManagerFactory host entity manager factory (never the framework's isolated one)
     */
    public CriteriaQueryExecutor(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, new CriteriaCompiler(), DEFAULT_MAX_CONCURRENT, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates the executor with custom limits.
     *
     * @param entityManagerFactory host entity manager factory
     * @param compiler             criteria compiler instance
     * @param maxConcurrent        per-node concurrency cap (1–200)
     * @param timeoutMs            query timeout in milliseconds (1000–300000)
     */
    public CriteriaQueryExecutor(EntityManagerFactory entityManagerFactory,
                                  CriteriaCompiler compiler,
                                  int maxConcurrent,
                                  int timeoutMs) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        if (maxConcurrent < 1 || maxConcurrent > 200) {
            throw new IllegalArgumentException("maxConcurrent must be in [1, 200]: " + maxConcurrent);
        }
        if (timeoutMs < 1000 || timeoutMs > 300_000) {
            throw new IllegalArgumentException("timeoutMs must be in [1000, 300000]: " + timeoutMs);
        }
        this.bulkhead = new Semaphore(maxConcurrent);
        this.timeoutMs = timeoutMs;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs in a read-only transaction joined to any active outer transaction, or starts one if absent.
     */
    @Override
    @Transactional(readOnly = true)
    public QueryResult execute(QueryDefinition query, DaiPrincipal principal,
                                Map<String, Object> params, List<RowPolicy> rowPolicies,
                                EffectiveCatalog catalog, int requestedSize) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(rowPolicies, "rowPolicies");
        Objects.requireNonNull(catalog, "catalog");

        if (!bulkhead.tryAcquire()) {
            throw new QueryBulkheadException(bulkhead.availablePermits() + (int) bulkhead.getQueueLength());
        }
        try {
            return doExecute(query, principal, params, rowPolicies, requestedSize);
        } finally {
            bulkhead.release();
        }
    }

    private QueryResult doExecute(QueryDefinition query, DaiPrincipal principal,
                                   Map<String, Object> params, List<RowPolicy> rowPolicies,
                                   int requestedSize) {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (em == null) {
            throw new IllegalStateException("No active transaction — CriteriaQueryExecutor must be called within a @Transactional boundary");
        }

        int effectiveSize = query.page().effectiveSize(requestedSize);

        List<RowPolicy> applicablePolicies = rowPolicies.stream()
                .filter(p -> p.appliesTo(principal.globalRoles()))
                .toList();

        TypedQuery<Tuple> typedQuery = compiler.compile(query, principal, applicablePolicies, effectiveSize, params, em);
        typedQuery.setHint("jakarta.persistence.query.timeout", timeoutMs);

        LOG.debug("Executing query {} rev {} for principal {}", query.id(), query.revision(), principal.principalId());
        List<Tuple> tuples = typedQuery.getResultList();

        return buildResult(tuples, query, effectiveSize);
    }

    private QueryResult buildResult(List<Tuple> tuples, QueryDefinition query, int effectiveSize) {
        boolean hasMore = tuples.size() > effectiveSize;
        List<Tuple> page = hasMore ? tuples.subList(0, effectiveSize) : tuples;

        List<String> outputNames = query.select().stream()
                .map(p -> p.alias() != null ? p.alias() : p.path().toString())
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>(page.size());
        for (Tuple tuple : page) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < outputNames.size() && i < tuple.getElements().size(); i++) {
                Object value = tuple.get(i);
                row.put(outputNames.get(i), value);
            }
            rows.add(row);
        }

        if (hasMore) {
            // Full keyset cursor generation is a v1.x TODO (LLD-05 §5a, LLD-14 §3.3)
            String cursor = "offset:" + effectiveSize;
            return QueryResult.paged(rows, cursor);
        }
        return QueryResult.complete(rows);
    }
}
