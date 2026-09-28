package com.springaimcpservercommon.query.validation;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.AttributePath;
import com.springaimcpservercommon.query.ast.FilterNode;
import com.springaimcpservercommon.query.ast.Operand;
import com.springaimcpservercommon.query.ast.Operator;
import com.springaimcpservercommon.query.ast.Projection;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.QueryParam;
import com.springaimcpservercommon.query.ast.RowPolicy;
import com.springaimcpservercommon.query.ast.SortSpec;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a {@link QueryDefinition} against the effective catalog (LLD-05 §3).
 *
 * <p>Validation runs at two points:
 * <ol>
 *   <li><strong>Publish-time</strong> ({@link #validateAtPublish}): checks the structure, paths, types
 *       and the author's classification clearance. A failure produces a 422.</li>
 *   <li><strong>Runtime</strong> ({@link #validateAtRuntime}): checks that mandatory filters are
 *       satisfied (row policies or arg constraints cover each mandatory attribute), that the catalog
 *       has not drifted, and that caller clearance is sufficient. A failure suspends the query or
 *       returns a runtime error.</li>
 * </ol>
 */
public final class QueryValidator {

    /** Maximum allowed elements in an IN / NOT_IN list. */
    public static final int MAX_IN_SIZE = 500;
    /** Default maximum join depth. */
    public static final int DEFAULT_MAX_JOIN_DEPTH = 3;
    /** Default maximum page size. */
    public static final int DEFAULT_MAX_PAGE_SIZE = 200;

    private final int maxJoinDepth;
    private final int maxPageSize;

    /**
     * Creates a validator with default limits.
     */
    public QueryValidator() {
        this(DEFAULT_MAX_JOIN_DEPTH, DEFAULT_MAX_PAGE_SIZE);
    }

    /**
     * Creates a validator with custom limits.
     *
     * @param maxJoinDepth maximum join depth allowed (LLD-05 §3)
     * @param maxPageSize  global maximum page size (LLD-05 §9)
     */
    public QueryValidator(int maxJoinDepth, int maxPageSize) {
        if (maxJoinDepth < 1) throw new IllegalArgumentException("maxJoinDepth must be >= 1");
        if (maxPageSize < 1) throw new IllegalArgumentException("maxPageSize must be >= 1");
        this.maxJoinDepth = maxJoinDepth;
        this.maxPageSize = maxPageSize;
    }

    /**
     * Validates a query definition at publish time.
     *
     * @param query   the definition to validate
     * @param catalog current effective catalog
     * @param author  author's principal (may be {@code null} for system/import operations skipping clearance checks)
     * @throws QueryValidationException if any violation is found
     */
    public void validateAtPublish(QueryDefinition query, EffectiveCatalog catalog, @Nullable DaiPrincipal author) {
        List<String> v = new ArrayList<>();

        EffectiveEntity rootEntity = resolveRoot(query, catalog, v);

        if (rootEntity != null) {
            validateProjections(query, rootEntity, catalog, author, v);
            validateFilter(query.where(), query, rootEntity, catalog, v);
            validateSortSpecs(query, rootEntity, catalog, v);
        }

        validatePageSpec(query, v);
        validateParamNames(query, v);

        if (!v.isEmpty()) {
            throw new QueryValidationException(v);
        }
    }

    /**
     * Validates a query at execution time (catalog drift, mandatory filters, caller clearance).
     *
     * @param query       the query to execute
     * @param catalog     current effective catalog
     * @param caller      executing principal
     * @param boundParams caller-supplied parameter values
     * @param rowPolicies applicable row policies for this entity and caller
     * @throws QueryValidationException if execution must be refused
     */
    public void validateAtRuntime(QueryDefinition query, EffectiveCatalog catalog,
                                  DaiPrincipal caller, Map<String, Object> boundParams,
                                  List<RowPolicy> rowPolicies) {
        List<String> v = new ArrayList<>();

        // Catalog hash drift check
        if (!query.catalogHash().equals(catalog.policyFingerprint())) {
            v.add("query was validated against catalog " + query.catalogHash()
                    + " but current catalog is " + catalog.policyFingerprint()
                    + "; republish required");
        }

        EffectiveEntity rootEntity = resolveRoot(query, catalog, v);
        if (rootEntity != null) {
            validateMandatoryFilters(query, rootEntity, boundParams, rowPolicies, v);
            validateCallerClearance(query, rootEntity, catalog, caller, v);
        }

        validateRequiredParams(query, boundParams, v);

        if (!v.isEmpty()) {
            throw new QueryValidationException(v);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private @Nullable EffectiveEntity resolveRoot(QueryDefinition query, EffectiveCatalog catalog,
                                                   List<String> violations) {
        Optional<EffectiveEntity> opt = catalog.entity(query.root());
        if (opt.isEmpty()) {
            violations.add("root entity not found in catalog: " + query.root());
            return null;
        }
        EffectiveEntity entity = opt.get();
        if (!entity.enabled()) {
            violations.add("root entity is disabled: " + query.root());
            return null;
        }
        return entity;
    }

    private void validateProjections(QueryDefinition query, EffectiveEntity root, EffectiveCatalog catalog,
                                      @Nullable DaiPrincipal author, List<String> violations) {
        for (Projection p : query.select()) {
            AttributeResolution res = resolveAttributePath(p.path(), root, catalog, violations);
            if (res != null) {
                if (res.attribute().sensitive()) {
                    violations.add("projected attribute is sensitive: " + p.path());
                }
                if (!res.attribute().enabled()) {
                    violations.add("projected attribute is disabled: " + p.path());
                }
                if (author != null && !author.isCleared(res.attribute().classification())) {
                    violations.add("author clearance " + author.clearance()
                            + " insufficient for attribute classification "
                            + res.attribute().classification() + " at " + p.path());
                }
            }
        }
    }

    private void validateFilter(@Nullable FilterNode node, QueryDefinition query,
                                 EffectiveEntity root, EffectiveCatalog catalog, List<String> violations) {
        if (node == null) return;
        switch (node) {
            case FilterNode.And(var children) -> children.forEach(c -> validateFilter(c, query, root, catalog, violations));
            case FilterNode.Or(var children) -> children.forEach(c -> validateFilter(c, query, root, catalog, violations));
            case FilterNode.Not(var child) -> validateFilter(child, query, root, catalog, violations);
            case FilterNode.Comparison comp -> validateComparison(comp, query, root, catalog, violations);
        }
    }

    private void validateComparison(FilterNode.Comparison comp, QueryDefinition query,
                                     EffectiveEntity root, EffectiveCatalog catalog, List<String> violations) {
        if (comp.path().joinDepth() > maxJoinDepth) {
            violations.add("filter path exceeds max join depth " + maxJoinDepth + ": " + comp.path());
        }
        AttributeResolution res = resolveAttributePath(comp.path(), root, catalog, violations);
        if (res != null) {
            if (res.attribute().sensitive()) {
                violations.add("filter on sensitive attribute not allowed: " + comp.path());
            }
            validateOperatorTypeCompatibility(comp.op(), res.attribute(), comp.path(), violations);
        }

        validateOperand(comp.op(), comp.operand(), query, violations);
    }

    private void validateOperatorTypeCompatibility(Operator op, EffectiveAttribute attr,
                                                    AttributePath path, List<String> violations) {
        if (op.isStringOnly()) {
            String javaType = attr.descriptor().javaType();
            if (!javaType.equals("java.lang.String") && !javaType.equals("String")) {
                violations.add("operator " + op + " requires a String attribute, but " + path + " is " + javaType);
            }
        }
    }

    private void validateOperand(Operator op, Operand operand, QueryDefinition query, List<String> violations) {
        if (op.isUnary()) {
            return; // operand is ignored
        }
        switch (operand) {
            case Operand.Literal(var value) -> {
                if (op.isMultiValue()) {
                    if (!(value instanceof List<?> list)) {
                        violations.add("operator " + op + " requires a list literal, got " + (value == null ? "null" : value.getClass().getSimpleName()));
                    } else {
                        if (list.isEmpty()) {
                            violations.add("operator " + op + " list literal must not be empty");
                        }
                        if ((op == Operator.IN || op == Operator.NOT_IN) && list.size() > MAX_IN_SIZE) {
                            violations.add("operator " + op + " list exceeds max size " + MAX_IN_SIZE + " (got " + list.size() + ")");
                        }
                        if (op == Operator.BETWEEN && list.size() != 2) {
                            violations.add("BETWEEN requires exactly 2 elements, got " + list.size());
                        }
                    }
                }
                if ((op == Operator.LIKE_PREFIX || op == Operator.CONTAINS_CI) && value instanceof String s) {
                    if (s.contains("%") || s.contains("_")) {
                        violations.add("LIKE_PREFIX / CONTAINS_CI value must not contain raw wildcards '%' or '_': " + s);
                    }
                }
            }
            case Operand.ParamRef(var paramName) -> {
                if (query.param(paramName) == null) {
                    violations.add("operand references undeclared parameter '" + paramName + "'");
                }
            }
            case Operand.PrincipalAttr ignored -> {} // resolved at runtime; no publish-time check
        }
    }

    private void validateSortSpecs(QueryDefinition query, EffectiveEntity root, EffectiveCatalog catalog,
                                    List<String> violations) {
        if (query.page().keysetEnabled() && query.orderBy().isEmpty()) {
            violations.add("keyset pagination requires at least one ORDER BY column");
        }
        for (SortSpec s : query.orderBy()) {
            resolveAttributePath(s.path(), root, catalog, violations);
        }
    }

    private void validatePageSpec(QueryDefinition query, List<String> violations) {
        if (query.page().maxSize() > maxPageSize) {
            violations.add("page.maxSize " + query.page().maxSize() + " exceeds global max " + maxPageSize);
        }
    }

    private void validateParamNames(QueryDefinition query, List<String> violations) {
        Set<String> seen = new HashSet<>();
        for (QueryParam p : query.params()) {
            if (!seen.add(p.name())) {
                violations.add("duplicate parameter name: " + p.name());
            }
        }
    }

    private void validateMandatoryFilters(QueryDefinition query, EffectiveEntity root,
                                           Map<String, Object> boundParams,
                                           List<RowPolicy> rowPolicies, List<String> violations) {
        for (String mandatory : root.mandatoryFilters()) {
            if (!isMandatoryFilterCovered(mandatory, query, boundParams, rowPolicies)) {
                violations.add("mandatory filter '" + mandatory + "' for entity " + root.ref()
                        + " is not covered by a row policy, arg constraint, or server-bound parameter");
            }
        }
    }

    private boolean isMandatoryFilterCovered(String attributeName, QueryDefinition query,
                                              Map<String, Object> boundParams, List<RowPolicy> rowPolicies) {
        // Covered by a row policy predicate that directly constraints this attribute with EQ/IN + PrincipalAttr
        for (RowPolicy policy : rowPolicies) {
            if (policyCoversAttribute(policy.predicate(), attributeName)) {
                return true;
            }
        }
        // Covered by a PrincipalAttr EQ/IN comparison in the query's own where clause
        if (query.where() != null && filterNodeCoversPrincipalAttr(query.where(), attributeName)) {
            return true;
        }
        // Covered by a bound parameter with an EQ/IN comparison
        return query.where() != null && filterNodeCoversBoundParam(query.where(), attributeName, boundParams);
    }

    private boolean policyCoversAttribute(FilterNode node, String attributeName) {
        return switch (node) {
            case FilterNode.And(var children) -> children.stream().anyMatch(c -> policyCoversAttribute(c, attributeName));
            case FilterNode.Or ignored -> false; // OR doesn't guarantee coverage
            case FilterNode.Not ignored -> false;
            case FilterNode.Comparison c ->
                    (c.op() == Operator.EQ || c.op() == Operator.IN)
                            && c.path().segments().size() == 1
                            && c.path().attributeName().equals(attributeName)
                            && c.operand() instanceof Operand.PrincipalAttr;
        };
    }

    private boolean filterNodeCoversPrincipalAttr(FilterNode node, String attributeName) {
        return switch (node) {
            case FilterNode.And(var children) -> children.stream().anyMatch(c -> filterNodeCoversPrincipalAttr(c, attributeName));
            case FilterNode.Or ignored -> false;
            case FilterNode.Not ignored -> false;
            case FilterNode.Comparison c ->
                    (c.op() == Operator.EQ || c.op() == Operator.IN)
                            && c.path().segments().size() == 1
                            && c.path().attributeName().equals(attributeName)
                            && c.operand() instanceof Operand.PrincipalAttr;
        };
    }

    private boolean filterNodeCoversBoundParam(FilterNode node, String attributeName, Map<String, Object> boundParams) {
        return switch (node) {
            case FilterNode.And(var children) -> children.stream().anyMatch(c -> filterNodeCoversBoundParam(c, attributeName, boundParams));
            case FilterNode.Or ignored -> false;
            case FilterNode.Not ignored -> false;
            case FilterNode.Comparison c ->
                    (c.op() == Operator.EQ || c.op() == Operator.IN)
                            && c.path().segments().size() == 1
                            && c.path().attributeName().equals(attributeName)
                            && c.operand() instanceof Operand.ParamRef(var name)
                            && boundParams.containsKey(name);
        };
    }

    private void validateCallerClearance(QueryDefinition query, EffectiveEntity root,
                                          EffectiveCatalog catalog, DaiPrincipal caller,
                                          List<String> violations) {
        for (Projection p : query.select()) {
            AttributeResolution res = resolveAttributePath(p.path(), root, catalog, new ArrayList<>());
            if (res != null) {
                Classification attrClass = res.attribute().classification();
                if (!caller.isCleared(attrClass)) {
                    violations.add("caller clearance " + caller.clearance()
                            + " insufficient for attribute " + p.path()
                            + " with classification " + attrClass);
                }
            }
        }
    }

    private void validateRequiredParams(QueryDefinition query, Map<String, Object> boundParams,
                                         List<String> violations) {
        for (QueryParam p : query.params()) {
            if (p.required() && !boundParams.containsKey(p.name())) {
                violations.add("required parameter '" + p.name() + "' not supplied");
            }
        }
    }

    /**
     * Resolves an attribute path against the root entity, following relations. Adds violations on error.
     */
    private @Nullable AttributeResolution resolveAttributePath(AttributePath path, EffectiveEntity root,
                                                                EffectiveCatalog catalog, List<String> violations) {
        if (path.joinDepth() > maxJoinDepth) {
            violations.add("attribute path exceeds max join depth " + maxJoinDepth + ": " + path);
            return null;
        }

        EffectiveEntity current = root;
        List<String> segments = path.segments();

        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            RelationDescriptor relation = findRelation(current, segment);
            if (relation == null) {
                violations.add("relation '" + segment + "' not found on entity " + current.ref()
                        + " at path " + path);
                return null;
            }
            Optional<EffectiveEntity> next = catalog.entity(relation.target());
            if (next.isEmpty() || !next.get().enabled()) {
                violations.add("related entity " + relation.target() + " not exposed at path " + path);
                return null;
            }
            current = next.get();
        }

        String attrName = path.attributeName();
        Optional<EffectiveAttribute> attrOpt = current.attribute(attrName);
        if (attrOpt.isEmpty()) {
            violations.add("attribute '" + attrName + "' not found in entity " + current.ref() + " at path " + path);
            return null;
        }
        return new AttributeResolution(current, attrOpt.get());
    }

    private static @Nullable RelationDescriptor findRelation(EffectiveEntity entity, String name) {
        for (RelationDescriptor r : entity.relations()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    /** Intermediate result of attribute path resolution. */
    private record AttributeResolution(EffectiveEntity entity, EffectiveAttribute attribute) {}
}
