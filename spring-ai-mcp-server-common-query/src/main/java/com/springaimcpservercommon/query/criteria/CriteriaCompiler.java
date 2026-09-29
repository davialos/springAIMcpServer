package com.springaimcpservercommon.query.criteria;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.AttributePath;
import com.springaimcpservercommon.query.ast.FilterNode;
import com.springaimcpservercommon.query.ast.Operand;
import com.springaimcpservercommon.query.ast.Operator;
import com.springaimcpservercommon.query.ast.Projection;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.RowPolicy;
import com.springaimcpservercommon.query.ast.SortSpec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compiles a {@link QueryDefinition} + row policies to a JPA {@link CriteriaQuery}{@code <Tuple>}
 * ready for execution (LLD-05 §4).
 *
 * <p>Design:
 * <ul>
 *   <li>The entity class is resolved via {@link Class#forName(String)} against the context classloader.</li>
 *   <li>Joins are cached by path string (LEFT OUTER for projections, INNER for mandatory filter paths).</li>
 *   <li>{@link Operand.PrincipalAttr} values are resolved from the principal's attributes map and inlined
 *       as {@code cb.literal(...)}; absence of the attribute → {@code cb.disjunction()} (always false, fail-closed).</li>
 *   <li>{@link Operand.ParamRef} values become named {@link jakarta.persistence.criteria.ParameterExpression}s;
 *       the caller binds them via {@link TypedQuery#setParameter(String, Object)}.</li>
 *   <li>Row policy predicates are AND-ed to the final WHERE clause after all other predicates.</li>
 *   <li>The query is always {@code SELECT DISTINCT} when any join may produce duplicates (to-many joins present).</li>
 *   <li>When a {@code keysetPosition} is supplied the compiler appends a row-value keyset predicate
 *       (LLD-05 §5a) so that only rows <em>after</em> the cursor position are returned. Hidden
 *       sort-key columns ({@code _ks_0}, {@code _ks_1}, …) are appended to SELECT so the executor
 *       can extract last-row sort values for the next cursor without a second query.</li>
 * </ul>
 */
public final class CriteriaCompiler {

    private static final Logger LOG = LoggerFactory.getLogger(CriteriaCompiler.class);

    /**
     * Compiles the query definition and applicable row policies to a {@link TypedQuery}.
     *
     * <p>When {@code keysetPosition} is non-null it must have the same number of elements as
     * {@code query.orderBy()}. Each element is the typed sort-key value from the last row of the
     * previous page (as decoded by {@link CursorCodec}); a null element causes the keyset clause
     * to be truncated at that position.
     *
     * @param query           validated query definition
     * @param principal       calling principal (for PrincipalAttr operand resolution)
     * @param rowPolicies     row policies whose predicates must be AND-ed on
     * @param effectiveLimit  effective page limit (already computed as min of caps)
     * @param params          caller-supplied parameter values by name (for ParamRef operands)
     * @param em              entity manager to compile against
     * @param keysetPosition  sort key values from the previous page's last row, or {@code null}
     *                        for the first page
     * @return a ready-to-execute typed query with all predicates inlined
     * @throws CriteriaCompilationException if the entity class cannot be resolved or a path is invalid
     */
    public TypedQuery<Tuple> compile(QueryDefinition query, DaiPrincipal principal,
                                      List<RowPolicy> rowPolicies, int effectiveLimit,
                                      Map<String, Object> params, EntityManager em,
                                      @Nullable List<@Nullable Object> keysetPosition) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(rowPolicies, "rowPolicies");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(em, "em");

        Class<?> entityClass = resolveEntityClass(query.root().value());

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<?> root = cq.from(entityClass);

        JoinCache joinCache = new JoinCache(root);

        // Build projected selection columns
        List<Selection<?>> selections = buildSelections(query.select(), root, joinCache, cb);

        // Build sort paths once; reuse for ORDER BY, hidden SELECT columns and keyset predicate
        List<SortSpec> sortSpecs = query.orderBy();
        List<Path<?>> sortPaths = new ArrayList<>(sortSpecs.size());
        for (SortSpec spec : sortSpecs) {
            sortPaths.add(buildPath(spec.path(), root, joinCache, false));
        }

        // Append hidden sort-key columns (_ks_N) so the executor can read last-row sort values
        for (int idx = 0; idx < sortSpecs.size(); idx++) {
            selections.add(sortPaths.get(idx).alias("_ks_" + idx));
        }
        cq.multiselect(selections);

        // Build WHERE predicates
        List<Predicate> predicates = new ArrayList<>();
        if (query.where() != null) {
            predicates.add(compileFilter(query.where(), root, joinCache, cb, principal, params, query));
        }
        for (RowPolicy policy : rowPolicies) {
            predicates.add(compileFilter(policy.predicate(), root, joinCache, cb, principal, params, query));
        }

        // Keyset predicate — only when position is supplied and sizes match
        if (keysetPosition != null
                && !sortSpecs.isEmpty()
                && keysetPosition.size() == sortSpecs.size()) {
            Predicate keysetPred = buildKeysetPredicate(cb, sortSpecs, sortPaths, keysetPosition);
            if (keysetPred != null) {
                predicates.add(keysetPred);
            }
        }

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        // DISTINCT if any to-many join may inflate results
        if (joinCache.hasToManyJoin()) {
            cq.distinct(true);
        }

        // ORDER BY (using pre-built sort paths)
        if (!sortSpecs.isEmpty()) {
            List<Order> orders = new ArrayList<>(sortSpecs.size());
            for (int idx = 0; idx < sortSpecs.size(); idx++) {
                orders.add(sortSpecs.get(idx).descending()
                        ? cb.desc(sortPaths.get(idx))
                        : cb.asc(sortPaths.get(idx)));
            }
            cq.orderBy(orders);
        }

        TypedQuery<Tuple> typedQuery = em.createQuery(cq);
        typedQuery.setMaxResults(effectiveLimit + 1); // fetch +1 to detect hasMore
        typedQuery.setHint("jakarta.persistence.query.timeout", 5000); // overridden by executor
        typedQuery.setHint("org.hibernate.readOnly", true);
        typedQuery.setHint("org.hibernate.flushMode", "COMMIT");
        typedQuery.setHint(CompiledQueryHints.OUTPUT_NAMES,
                query.select().stream().map(Projection::outputName).toList());

        return typedQuery;
    }

    // ── keyset predicate ──────────────────────────────────────────────────────

    /**
     * Builds the row-value keyset predicate for keys {@code k0..kN} at position {@code v0..vN}:
     * <pre>
     * (k0 &gt; v0)
     * OR (k0 = v0 AND k1 &lt; v1)   -- DESC → less-than
     * OR (k0 = v0 AND k1 = v1 AND k2 &gt; v2)
     * ...
     * </pre>
     *
     * <p>Stops expanding at the first {@code null} position value (conservative: no row after a null
     * sort key can be safely expressed as a single predicate without SQL {@code IS NULL} handling).
     *
     * @return the OR predicate, or {@code null} if no valid OR clause could be constructed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Predicate buildKeysetPredicate(CriteriaBuilder cb,
                                                      List<SortSpec> sortSpecs,
                                                      List<Path<?>> sortPaths,
                                                      List<@Nullable Object> position) {
        List<Predicate> orClauses = new ArrayList<>(sortSpecs.size());
        for (int i = 0; i < sortSpecs.size(); i++) {
            @Nullable Object rawVal = position.get(i);
            if (rawVal == null) break; // stop at first null — see Javadoc

            Object val = coerceValue(rawVal, sortPaths.get(i).getJavaType());

            // Equality predicates for k0..k(i-1)
            List<Predicate> andPreds = new ArrayList<>(i + 1);
            for (int j = 0; j < i; j++) {
                Object prevVal = coerceValue(position.get(j), sortPaths.get(j).getJavaType());
                andPreds.add(cb.equal(sortPaths.get(j), prevVal));
            }

            // Inequality predicate for ki: ASC → GT, DESC → LT
            Expression<Comparable> expr = (Expression<Comparable>) sortPaths.get(i);
            Comparable compVal = (Comparable) val;
            andPreds.add(sortSpecs.get(i).descending()
                    ? cb.lessThan(expr, compVal)
                    : cb.greaterThan(expr, compVal));

            orClauses.add(andPreds.size() == 1
                    ? andPreds.get(0)
                    : cb.and(andPreds.toArray(Predicate[]::new)));
        }
        if (orClauses.isEmpty()) return null;
        return orClauses.size() == 1
                ? orClauses.get(0)
                : cb.or(orClauses.toArray(Predicate[]::new));
    }

    /**
     * Coerces a cursor-decoded value to the Java type reported by the JPA path.
     * Handles the common mismatch where {@link CursorCodec} stores all integers as {@code Long}
     * but the path type may be {@code Integer} or {@code Short}.
     */
    @Nullable
    private static Object coerceValue(@Nullable Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;
        if (value instanceof Long n) {
            if (targetType == Integer.class) return n.intValue();
            if (targetType == Short.class) return n.shortValue();
            if (targetType == Byte.class) return n.byteValue();
            if (targetType == Double.class) return n.doubleValue();
            if (targetType == Float.class) return n.floatValue();
        }
        if (value instanceof Double d && targetType == Float.class) return d.floatValue();
        if (value instanceof String s && targetType == UUID.class) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {}
        }
        return value; // best-effort: let JPA handle remaining mismatches
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private Class<?> resolveEntityClass(String className) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return cl != null ? Class.forName(className, true, cl) : Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new CriteriaCompilationException("Entity class not found: " + className, e);
        }
    }

    private List<Selection<?>> buildSelections(List<Projection> projections, Root<?> root,
                                                JoinCache joinCache, CriteriaBuilder cb) {
        List<Selection<?>> selections = new ArrayList<>();
        for (Projection p : projections) {
            Path<?> path = buildPath(p.path(), root, joinCache, false);
            selections.add(path.alias(p.outputName()));
        }
        return selections;
    }

    @SuppressWarnings("unchecked")
    private Predicate compileFilter(FilterNode node, Root<?> root, JoinCache joinCache,
                                    CriteriaBuilder cb, DaiPrincipal principal,
                                    Map<String, Object> params, QueryDefinition query) {
        return switch (node) {
            case FilterNode.And(var children) -> {
                Predicate[] preds = children.stream()
                        .map(c -> compileFilter(c, root, joinCache, cb, principal, params, query))
                        .toArray(Predicate[]::new);
                yield cb.and(preds);
            }
            case FilterNode.Or(var children) -> {
                Predicate[] preds = children.stream()
                        .map(c -> compileFilter(c, root, joinCache, cb, principal, params, query))
                        .toArray(Predicate[]::new);
                yield cb.or(preds);
            }
            case FilterNode.Not(var child) ->
                    cb.not(compileFilter(child, root, joinCache, cb, principal, params, query));
            case FilterNode.Comparison comp ->
                    compileComparison(comp, root, joinCache, cb, principal, params, query);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate compileComparison(FilterNode.Comparison comp, Root<?> root, JoinCache joinCache,
                                         CriteriaBuilder cb, DaiPrincipal principal,
                                         Map<String, Object> params, QueryDefinition query) {
        Path<Object> attrPath = buildPath(comp.path(), root, joinCache, true);
        Operator op = comp.op();

        if (op.isUnary()) {
            return op == Operator.IS_NULL ? cb.isNull(attrPath) : cb.isNotNull(attrPath);
        }

        Object resolvedValue = resolveOperand(comp.operand(), principal, params, query);
        if (resolvedValue == FAIL_CLOSED_SENTINEL) {
            LOG.warn("PrincipalAttr missing for operand in query {}: returning always-false predicate", query.id());
            return cb.disjunction();
        }

        return switch (op) {
            case EQ -> cb.equal(attrPath, resolvedValue);
            case NE -> cb.notEqual(attrPath, resolvedValue);
            case LT -> cb.lessThan((Expression<Comparable>) attrPath, (Comparable) resolvedValue);
            case LE -> cb.lessThanOrEqualTo((Expression<Comparable>) attrPath, (Comparable) resolvedValue);
            case GT -> cb.greaterThan((Expression<Comparable>) attrPath, (Comparable) resolvedValue);
            case GE -> cb.greaterThanOrEqualTo((Expression<Comparable>) attrPath, (Comparable) resolvedValue);
            case IN -> attrPath.in(asList(resolvedValue));
            case NOT_IN -> cb.not(attrPath.in(asList(resolvedValue)));
            case LIKE_PREFIX -> cb.like((Expression<String>) attrPath, escapeLike(resolvedValue.toString()) + "%");
            case CONTAINS_CI -> cb.like(
                    cb.lower((Expression<String>) attrPath),
                    "%" + escapeLike(resolvedValue.toString().toLowerCase(java.util.Locale.ROOT)) + "%");
            case BETWEEN -> {
                List<?> range = asList(resolvedValue);
                yield cb.between((Expression<Comparable>) attrPath,
                        (Comparable) range.get(0), (Comparable) range.get(1));
            }
            case IS_NULL, NOT_NULL -> throw new IllegalStateException("unreachable");
        };
    }

    /** Sentinel value meaning "PrincipalAttr was missing → fail closed". */
    private static final Object FAIL_CLOSED_SENTINEL = new Object();

    private Object resolveOperand(Operand operand, DaiPrincipal principal,
                                   Map<String, Object> params, QueryDefinition query) {
        return switch (operand) {
            case Operand.Literal(var value) -> Objects.requireNonNullElse(value, "");
            case Operand.PrincipalAttr(var attrName) -> {
                Object val = principal.attributes().get(attrName);
                yield val != null ? val : FAIL_CLOSED_SENTINEL;
            }
            case Operand.ParamRef(var name) -> {
                Object val = params.get(name);
                if (val == null) {
                    var declared = query.param(name);
                    val = declared != null ? declared.defaultValue() : null;
                }
                yield val != null ? val : FAIL_CLOSED_SENTINEL;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Path<Object> buildPath(AttributePath path, Root<?> root, JoinCache joinCache, boolean forFilter) {
        From<?, ?> current = root;
        for (String segment : path.joinPath()) {
            current = joinCache.getOrJoin(current, segment, forFilter ? JoinType.INNER : JoinType.LEFT);
        }
        return current.get(path.attributeName());
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> l) return l;
        return Collections.singletonList(value);
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Caches join instances by (from-alias, relation-name) to avoid duplicate JOINs. */
    private static final class JoinCache {
        private final Map<String, Join<?, ?>> joins = new LinkedHashMap<>();
        private boolean hasToMany = false;
        private final Root<?> root;

        JoinCache(Root<?> root) {
            this.root = root;
        }

        Join<?, ?> getOrJoin(From<?, ?> from, String relation, JoinType type) {
            String key = from.getAlias() != null ? from.getAlias() + "." + relation : relation;
            return joins.computeIfAbsent(key, k -> {
                Join<?, ?> j = from.join(relation, type);
                try {
                    jakarta.persistence.metamodel.Attribute<?, ?> attr =
                            from.getModel().getAttribute(relation);
                    if (attr.isCollection()) hasToMany = true;
                } catch (IllegalArgumentException ignored) {}
                return j;
            });
        }

        boolean hasToManyJoin() {
            return hasToMany;
        }
    }
}
