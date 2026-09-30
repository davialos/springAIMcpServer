package com.springaimcpservercommon.query.criteria;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.RowPolicy;
import com.springaimcpservercommon.query.ast.SortSpec;
import com.springaimcpservercommon.query.execution.QueryBulkheadException;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.jspecify.annotations.Nullable;
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
 *   <li>HMAC-signed keyset cursor generation and consumption (LLD-05 §5a, LLD-14 §3.3).</li>
 * </ul>
 *
 * <h3>Cursor protocol</h3>
 * Callers pass a previously issued cursor by adding it to the {@code params} map under the
 * reserved key {@code "_cursor"}. The executor strips this key before forwarding params to
 * the compiler. The cursor is signed with {@link CursorCodec} and binds to the query id
 * and revision — a cursor from a different query or an older revision is ignored (first page).
 *
 * <p>Keyset cursors are preferred when the query has an ORDER BY clause and all last-row sort
 * values are non-null. When keyset is unavailable the executor falls back to a signed offset
 * cursor so multi-page traversal still works, albeit with O(N) seek cost.
 *
 * <p>Declared as a Spring bean in {@code autoconfigure} — not annotated here.
 */
public class CriteriaQueryExecutor implements QueryExecutor {

    /** Default per-node concurrency cap. */
    public static final int DEFAULT_MAX_CONCURRENT = 20;
    /** Default query timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    /** Reserved parameter key carrying an opaque cursor for the next-page request. */
    public static final String CURSOR_PARAM = "_cursor";

    private static final Logger LOG = LoggerFactory.getLogger(CriteriaQueryExecutor.class);

    private final EntityManagerFactory entityManagerFactory;
    private final CriteriaCompiler compiler;
    private final CursorCodec codec;
    private final Semaphore bulkhead;
    private final int timeoutMs;

    /**
     * Creates the executor with default limits and a random per-instance cursor signing key.
     *
     * @param entityManagerFactory host entity manager factory (never the framework's isolated one)
     */
    public CriteriaQueryExecutor(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, new CriteriaCompiler(), DEFAULT_MAX_CONCURRENT, DEFAULT_TIMEOUT_MS,
                new CursorCodec());
    }

    /**
     * Creates the executor with custom limits and a random per-instance cursor signing key.
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
        this(entityManagerFactory, compiler, maxConcurrent, timeoutMs, new CursorCodec());
    }

    /**
     * Creates the executor with custom limits and a supplied cursor signing key.
     * Use this constructor when running multiple replicas behind a load balancer so that cursors
     * issued by one node can be verified by any other node.
     *
     * @param entityManagerFactory host entity manager factory
     * @param compiler             criteria compiler instance
     * @param maxConcurrent        per-node concurrency cap (1–200)
     * @param timeoutMs            query timeout in milliseconds (1000–300000)
     * @param cursorSigningKey     shared HMAC key for cursor signing (at least 32 bytes)
     */
    public CriteriaQueryExecutor(EntityManagerFactory entityManagerFactory,
                                  CriteriaCompiler compiler,
                                  int maxConcurrent,
                                  int timeoutMs,
                                  byte[] cursorSigningKey) {
        this(entityManagerFactory, compiler, maxConcurrent, timeoutMs, new CursorCodec(cursorSigningKey));
    }

    private CriteriaQueryExecutor(EntityManagerFactory entityManagerFactory,
                                   CriteriaCompiler compiler,
                                   int maxConcurrent,
                                   int timeoutMs,
                                   CursorCodec codec) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.codec = Objects.requireNonNull(codec, "codec");
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
            return doExecute(query, principal, params, rowPolicies, requestedSize, catalog);
        } finally {
            bulkhead.release();
        }
    }

    private QueryResult doExecute(QueryDefinition query, DaiPrincipal principal,
                                   Map<String, Object> params, List<RowPolicy> rowPolicies,
                                   int requestedSize, EffectiveCatalog catalog) {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (em == null) {
            throw new IllegalStateException(
                    "No active transaction — CriteriaQueryExecutor must be called within a @Transactional boundary");
        }

        int effectiveSize = query.page().effectiveSize(requestedSize);

        // Extract and strip the reserved "_cursor" parameter before forwarding to the compiler
        Object rawCursor = params.get(CURSOR_PARAM);
        Map<String, Object> queryParams = params;
        @Nullable List<@Nullable Object> keysetPosition = null;
        int currentOffset = 0;

        if (rawCursor instanceof String cursorStr) {
            queryParams = new LinkedHashMap<>(params);
            queryParams.remove(CURSOR_PARAM);

            // Prefer keyset cursor; fall back to signed offset cursor
            keysetPosition = codec.decode(query, cursorStr);
            if (keysetPosition == null) {
                int decodedOffset = codec.decodeOffset(cursorStr);
                if (decodedOffset > 0) {
                    currentOffset = decodedOffset;
                }
                // An unrecognised or tampered cursor is silently ignored (first-page semantics)
            }
        }

        List<RowPolicy> applicablePolicies = rowPolicies.stream()
                .filter(p -> p.appliesTo(principal.globalRoles()))
                .toList();

        List<EffectiveAttribute> context = rowContext(query, principal, catalog);
        TypedQuery<Tuple> typedQuery = compiler.compile(
                query, principal, applicablePolicies, effectiveSize, queryParams, em, keysetPosition,
                context.stream().map(EffectiveAttribute::name).toList());
        typedQuery.setHint("jakarta.persistence.query.timeout", timeoutMs);

        // Apply offset fallback only when keyset was not available
        if (keysetPosition == null && currentOffset > 0) {
            typedQuery.setFirstResult(currentOffset);
        }

        LOG.debug("Executing query {} rev {} for principal {}", query.id(), query.revision(), principal.principalId());
        List<Tuple> tuples = typedQuery.getResultList();

        return buildResult(tuples, query, effectiveSize, currentOffset, context);
    }

    /**
     * The per-record context columns of the query's root entity that this caller may receive: marked
     * {@code @AiRowContext}, enabled, not sensitive, and not classified above the caller's clearance.
     */
    private static List<EffectiveAttribute> rowContext(QueryDefinition query, DaiPrincipal principal,
                                                        EffectiveCatalog catalog) {
        return catalog.entity(query.root())
                .filter(EffectiveEntity::enabled)
                .map(entity -> entity.attributes().values().stream()
                        .filter(EffectiveAttribute::rowContext)
                        .filter(a -> a.classification().compareTo(principal.clearance()) <= 0)
                        .toList())
                .orElse(List.of());
    }

    private QueryResult buildResult(List<Tuple> tuples, QueryDefinition query,
                                     int effectiveSize, int currentOffset, List<EffectiveAttribute> context) {
        boolean hasMore = tuples.size() > effectiveSize;
        List<Tuple> page = hasMore ? tuples.subList(0, effectiveSize) : tuples;

        int projectionCount = query.select().size();
        List<String> outputNames = query.select().stream()
                .map(p -> p.alias() != null ? p.alias() : p.path().toString())
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>(page.size());
        for (Tuple tuple : page) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < outputNames.size() && i < tuple.getElements().size(); i++) {
                row.put(outputNames.get(i), tuple.get(i));
            }
            Map<String, Object> notes = contextOf(tuple, projectionCount + query.orderBy().size(), context);
            if (!notes.isEmpty()) {
                row.put(QueryResult.CONTEXT_KEY, notes);
            }
            rows.add(row);
        }

        if (hasMore) {
            List<SortSpec> sortSpecs = query.orderBy();
            if (!sortSpecs.isEmpty() && !page.isEmpty()) {
                Tuple lastTuple = page.get(page.size() - 1);
                List<@Nullable Object> sortValues = extractSortKeyValues(lastTuple, projectionCount, sortSpecs.size());
                boolean allNonNull = sortValues.stream().noneMatch(Objects::isNull);
                if (allNonNull) {
                    try {
                        return QueryResult.paged(rows, codec.encode(query, sortValues));
                    } catch (IllegalArgumentException ignored) {
                        // Unsupported sort value type → fall through to signed offset cursor
                        LOG.debug("Query {} has unsupported sort value type; falling back to offset cursor",
                                query.id());
                    }
                }
            }
            // Fallback: signed offset cursor — encodes the absolute start offset for the next page
            return QueryResult.paged(rows, codec.encodeOffset(currentOffset + effectiveSize));
        }
        return QueryResult.complete(rows);
    }

    private static Map<String, Object> contextOf(Tuple tuple, int offset, List<EffectiveAttribute> context) {
        Map<String, Object> notes = new LinkedHashMap<>();
        for (int i = 0; i < context.size() && offset + i < tuple.getElements().size(); i++) {
            Object value = tuple.get(offset + i);
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            EffectiveAttribute attribute = context.get(i);
            String text = value.toString().strip();
            int max = attribute.descriptor().rowContextMaxChars();
            if (text.length() > max) {
                text = text.substring(0, max) + "…";
            }
            String label = attribute.descriptor().rowContextLabel();
            notes.put(label == null ? attribute.name() : label, text);
        }
        return notes;
    }

    private static List<@Nullable Object> extractSortKeyValues(Tuple tuple,
                                                                 int projectionCount,
                                                                 int sortKeyCount) {
        List<@Nullable Object> values = new ArrayList<>(sortKeyCount);
        int tupleSize = tuple.getElements().size();
        for (int i = 0; i < sortKeyCount; i++) {
            int idx = projectionCount + i;
            values.add(idx < tupleSize ? tuple.get(idx) : null);
        }
        return values;
    }
}
