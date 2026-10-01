package com.springaimcpservercommon.query.adhoc;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;
import com.springaimcpservercommon.query.ast.AttributePath;
import com.springaimcpservercommon.query.ast.FilterNode;
import com.springaimcpservercommon.query.ast.Operand;
import com.springaimcpservercommon.query.ast.Operator;
import com.springaimcpservercommon.query.ast.PageSpec;
import com.springaimcpservercommon.query.ast.Projection;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.SortSpec;
import com.springaimcpservercommon.query.criteria.CriteriaQueryExecutor;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import com.springaimcpservercommon.query.validation.QueryValidationException;
import com.springaimcpservercommon.query.validation.QueryValidator;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a model read real data with queries it builds itself (LLD-05 §12): it {@link #describe describes} the
 * entities the catalog exposes to AI, {@link #check checks} a request written in a small JSON format and compiles it
 * to the same {@link QueryDefinition} AST published queries use, and {@link #execute runs} it through the
 * {@link QueryExecutor} (JPA Criteria, read-only, bulkhead, timeout, keyset pagination).
 *
 * <p>Model-built queries are held to stricter rules than authored ones, because nobody reviewed them:
 * <ul>
 *   <li>only entities and attributes exposed to AI, not sensitive, not disabled and not classified above the caller's
 *       clearance can be selected, filtered or sorted on, and only through relations to entities that are as well;</li>
 *   <li>select and sort cannot traverse to-many relations (filters can, and the result is made distinct);</li>
 *   <li>values are literals converted to the attribute's type, or the caller's own principal attributes, never
 *       anything bound from another user;</li>
 *   <li>an entity's mandatory filters must be bound to the caller's principal attributes;</li>
 *   <li>size limits: {@value #MAX_SELECT} columns, {@value #MAX_CONDITIONS} conditions, nesting
 *       {@value #MAX_NESTING}, pages capped by the binding, the entity and the global limit.</li>
 * </ul>
 * Every request is then also run through {@link QueryValidator} (publish-time and runtime checks).
 *
 * <p>Request format:
 * <pre>{@code
 * {"entity": "Order",
 *  "select": ["id", "status", "customer.name"],                    // optional: default all visible columns
 *  "where": {"all": [{"path": "status", "op": "IN", "value": ["OPEN", "SHIPPED"]},
 *                    {"any": [{"path": "total", "op": "GT", "value": 100},
 *                             {"not": {"path": "notes", "op": "IS_NULL"}}]},
 *                    {"path": "customerId", "op": "EQ", "principal": "customerId"}]},
 *  "orderBy": [{"path": "createdAt", "direction": "desc"}],          // optional: default the identifier
 *  "limit": 20, "cursor": "<nextCursor of the previous page>"}
 * }</pre>
 * Thread-safe and stateless.
 */
public final class CriteriaQueryEngine {

    /** Most columns one query may select. */
    public static final int MAX_SELECT = 30;
    /** Most comparisons one query may contain. */
    public static final int MAX_CONDITIONS = 40;
    /** Deepest nesting of all/any/not groups. */
    public static final int MAX_NESTING = 6;
    /** Rows per page when the request does not say. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<Operator> EQUALITY = EnumSet.of(Operator.EQ, Operator.NE, Operator.IN, Operator.NOT_IN,
            Operator.IS_NULL, Operator.NOT_NULL);
    private static final Set<Operator> ORDERED = EnumSet.of(Operator.EQ, Operator.NE, Operator.LT, Operator.LE,
            Operator.GT, Operator.GE, Operator.IN, Operator.NOT_IN, Operator.BETWEEN, Operator.IS_NULL,
            Operator.NOT_NULL);
    private static final Set<Operator> TEXT = EnumSet.of(Operator.EQ, Operator.NE, Operator.LT, Operator.LE,
            Operator.GT, Operator.GE, Operator.IN, Operator.NOT_IN, Operator.LIKE_PREFIX, Operator.CONTAINS_CI,
            Operator.IS_NULL, Operator.NOT_NULL);
    private static final Set<Operator> BOOL = EnumSet.of(Operator.EQ, Operator.NE, Operator.IS_NULL,
            Operator.NOT_NULL);

    private final QueryValidator validator;
    private final int maxPageSize;

    /** An engine with the default validator and page limit. */
    public CriteriaQueryEngine() {
        this(new QueryValidator(), QueryValidator.DEFAULT_MAX_PAGE_SIZE);
    }

    /**
     * @param validator   the validator every compiled query must also pass
     * @param maxPageSize global maximum rows per page (LLD-05 §9)
     */
    public CriteriaQueryEngine(QueryValidator validator, int maxPageSize) {
        this.validator = Objects.requireNonNull(validator, "validator");
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be >= 1");
        }
        this.maxPageSize = maxPageSize;
    }

    // ── describe ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What the caller may query: without an entity name, a summary of every visible entity; with one, its columns
     * (type, meaning, allowed operators, enum values), its relations and its mandatory filters. Also lists the names
     * (never the values) of the caller's principal attributes usable with {@code "principal"}.
     *
     * @param scope      caller and binding limits
     * @param entityName entity to detail, or {@code null} for the summary
     * @return a JSON-ready map
     * @throws IllegalArgumentException when the named entity is not visible (the message lists the visible ones)
     */
    public Map<String, Object> describe(AdhocScope scope, @Nullable String entityName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (entityName == null || entityName.isBlank()) {
            List<Map<String, Object>> entities = new ArrayList<>();
            for (EffectiveEntity e : visibleEntities(scope)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("entity", e.name());
                m.put("description", e.description());
                if (!e.keywords().isEmpty()) {
                    m.put("keywords", e.keywords());
                }
                m.put("columns", visibleAttributes(e, scope).stream().map(EffectiveAttribute::name).toList());
                m.put("relations", visibleRelations(e, scope).stream().map(RelationDescriptor::name).toList());
                entities.add(m);
            }
            out.put("entities", entities);
            out.put("hint", "Call again with an entity name for its columns, types and operators.");
        } else {
            List<String> problems = new ArrayList<>();
            EffectiveEntity e = resolveEntity(entityName, scope, "entity", problems);
            if (e == null) {
                throw new IllegalArgumentException(String.join("; ", problems));
            }
            out.put("entity", detail(e, scope));
        }
        out.put("principalAttributes", scope.principal().attributes().keySet().stream().sorted().toList());
        out.put("maxRowsPerPage", scope.maxRows());
        return out;
    }

    private Map<String, Object> detail(EffectiveEntity e, AdhocScope scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", e.name());
        m.put("description", e.description());
        m.put("maxRowsPerPage", pageCap(e, scope));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (EffectiveAttribute a : visibleAttributes(e, scope)) {
            ValueType type = ValueType.of(a.descriptor().javaType());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", a.name());
            c.put("type", type.label);
            if (!a.meaning().isBlank()) {
                c.put("meaning", a.meaning());
            }
            if (type.enumValues != null) {
                c.put("values", type.enumValues);
            }
            if (a.descriptor().identifier()) {
                c.put("identifier", true);
            }
            c.put("operators", type.operators.stream().map(Enum::name).toList());
            columns.add(c);
        }
        m.put("columns", columns);
        List<Map<String, Object>> relations = new ArrayList<>();
        for (RelationDescriptor r : visibleRelations(e, scope)) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("name", r.name());
            rel.put("entity", scope.catalog().entity(r.target()).map(EffectiveEntity::name).orElse(r.target().value()));
            rel.put("many", r.kind().toMany());
            rel.put("use", r.kind().toMany() ? "filter only, e.g. \"" + r.name() + ".<column>\""
                    : "select, filter or sort, e.g. \"" + r.name() + ".<column>\"");
            relations.add(rel);
        }
        m.put("relations", relations);
        if (!e.mandatoryFilters().isEmpty()) {
            m.put("mandatoryFilters", e.mandatoryFilters());
            m.put("mandatoryFilterRule", "each needs {\"path\":\"<column>\",\"op\":\"EQ\",\"principal\":\"<one of "
                    + "principalAttributes>\"} in the top-level \"all\" group");
        }
        return m;
    }

    // ── check ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Checks a request and compiles it. Never throws for a bad request: problems are returned as errors a model can
     * act on.
     *
     * @param request the parsed JSON request
     * @param scope   caller and binding limits
     * @return the outcome
     */
    public CriteriaCheck check(Map<String, ?> request, AdhocScope scope) {
        Parse p = new Parse(scope);
        if (scope.catalog().failClosed()) {
            p.errors.add("The data model is temporarily unavailable (a policy layer failed to load).");
            return p.invalid();
        }
        Set<String> known = Set.of("entity", "select", "where", "orderBy", "limit", "cursor");
        for (String key : request.keySet()) {
            if (!known.contains(key)) {
                p.errors.add(key + ": unknown field; allowed: entity, select, where, orderBy, limit, cursor");
            }
        }
        Object entityArg = request.get("entity");
        if (!(entityArg instanceof String entityName) || entityName.isBlank()) {
            p.errors.add("entity: required, the name of an entity from the data model");
            return p.invalid();
        }
        EffectiveEntity root = resolveEntity(entityName, scope, "entity", p.errors);
        if (root == null) {
            return p.invalid();
        }
        p.root = root;
        p.normalized.put("entity", root.name());

        List<Projection> select = p.select(request.get("select"));
        FilterNode where = request.get("where") == null ? null : p.condition(request.get("where"), "where", 0);
        List<SortSpec> orderBy = p.orderBy(request.get("orderBy"), select);
        int pageSize = p.pageSize(request.get("limit"));
        String cursor = null;
        Object cursorArg = request.get("cursor");
        if (cursorArg instanceof String s && !s.isBlank()) {
            cursor = s;
        } else if (cursorArg != null) {
            p.errors.add("cursor: must be the nextCursor string returned with the previous page");
        }
        if (p.conditions > MAX_CONDITIONS) {
            p.errors.add("where: at most " + MAX_CONDITIONS + " conditions (found " + p.conditions + ")");
        }
        p.mandatoryFilters(where);
        if (!p.errors.isEmpty()) {
            return p.invalid();
        }

        String explain = explain(root, select, where, orderBy, pageSize);
        UUID id = UUID.nameUUIDFromBytes(("adhoc:" + scope.workspaceId() + ":" + root.ref() + ":"
                + explain.substring(0, explain.lastIndexOf(" LIMIT "))).getBytes(StandardCharsets.UTF_8));
        int cap = pageCap(root, scope);
        QueryDefinition query = new QueryDefinition(id, 0, scope.workspaceId(), root.ref(), select, where, orderBy,
                new PageSpec(Math.min(DEFAULT_PAGE_SIZE, cap), cap, true), List.of(), p.references,
                scope.catalog().policyFingerprint());
        try {
            validator.validateAtPublish(query, scope.catalog(), scope.principal());
            validator.validateAtRuntime(query, scope.catalog(), scope.principal(), Map.of(), List.of());
        } catch (QueryValidationException e) {
            e.violations().forEach(v -> p.errors.add("query: " + v));
            return p.invalid();
        }
        if (cursor != null) {
            p.normalized.put("cursor", cursor);
        }
        return new CriteriaCheck(true, List.of(), p.warnings, explain, p.normalized, query, pageSize, cursor);
    }

    // ── execute ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs a checked query as the caller.
     *
     * @param check    a valid check from {@link #check}
     * @param scope    the same scope it was checked with
     * @param executor the query executor (read-only transaction, bulkhead, timeout)
     * @return one page of rows; {@code nextCursor} continues it
     * @throws IllegalArgumentException when the check is not valid
     */
    public QueryResult execute(CriteriaCheck check, AdhocScope scope, QueryExecutor executor) {
        if (!check.valid() || check.query() == null) {
            throw new IllegalArgumentException("only a valid check can run");
        }
        Map<String, Object> params = check.cursor() == null ? Map.of()
                : Map.of(CriteriaQueryExecutor.CURSOR_PARAM, check.cursor());
        // row policies are not loaded yet for any query path (LLD-05 §5); mandatory filters are enforced by check()
        return executor.execute(check.query(), scope.principal(), params, List.of(), scope.catalog(),
                check.pageSize());
    }

    // ── catalog views ───────────────────────────────────────────────────────────────────────────────────────────

    private static List<EffectiveEntity> visibleEntities(AdhocScope scope) {
        return scope.catalog().entities().values().stream().filter(scope::sees)
                .sorted(Comparator.comparing(EffectiveEntity::name)).toList();
    }

    private static List<EffectiveAttribute> visibleAttributes(EffectiveEntity e, AdhocScope scope) {
        return e.attributes().values().stream()
                .filter(a -> a.exposable() && scope.principal().isCleared(a.classification()))
                .sorted(Comparator.comparing((EffectiveAttribute a) -> !a.descriptor().identifier())
                        .thenComparing(EffectiveAttribute::name))
                .toList();
    }

    private static List<RelationDescriptor> visibleRelations(EffectiveEntity e, AdhocScope scope) {
        return e.relations().stream()
                .filter(r -> scope.catalog().entity(r.target()).filter(scope::sees).isPresent())
                .toList();
    }

    private int pageCap(EffectiveEntity e, AdhocScope scope) {
        return Math.max(1, Math.min(Math.min(scope.maxRows(), e.maxLimit()), maxPageSize));
    }

    private static @Nullable EffectiveEntity resolveEntity(String name, AdhocScope scope, String where,
                                                           List<String> errors) {
        String wanted = name.trim();
        List<EffectiveEntity> visible = visibleEntities(scope);
        List<EffectiveEntity> matches = visible.stream()
                .filter(e -> e.name().equalsIgnoreCase(wanted) || e.descriptor().javaType().equals(wanted)
                        || e.ref().toString().equals(wanted))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            errors.add(where + ": '" + wanted + "' is ambiguous; use one of "
                    + matches.stream().map(e -> e.descriptor().javaType()).toList());
        } else {
            errors.add(where + ": no entity '" + wanted + "' you can query; available: "
                    + visible.stream().map(EffectiveEntity::name).toList());
        }
        return null;
    }

    // ── explain ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static String explain(EffectiveEntity root, List<Projection> select, @Nullable FilterNode where,
                                  List<SortSpec> orderBy, int pageSize) {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", select.stream().map(p -> p.path().toString()).toList()));
        sb.append(" FROM ").append(root.name());
        if (where != null) {
            sb.append(" WHERE ").append(explain(where));
        }
        sb.append(" ORDER BY ").append(String.join(", ",
                orderBy.stream().map(s -> s.path() + (s.descending() ? " DESC" : " ASC")).toList()));
        sb.append(" LIMIT ").append(pageSize);
        return sb.toString();
    }

    private static String explain(FilterNode node) {
        return switch (node) {
            case FilterNode.And(var children) -> "(" + String.join(" AND ", children.stream()
                    .map(CriteriaQueryEngine::explain).toList()) + ")";
            case FilterNode.Or(var children) -> "(" + String.join(" OR ", children.stream()
                    .map(CriteriaQueryEngine::explain).toList()) + ")";
            case FilterNode.Not(var child) -> "NOT " + explain(child);
            case FilterNode.Comparison c -> {
                String path = c.path().toString();
                String value = switch (c.operand()) {
                    case Operand.PrincipalAttr(var attr) -> "<your " + attr + ">";
                    case Operand.ParamRef(var name) -> ":" + name;
                    case Operand.Literal(var v) -> literal(v);
                };
                yield switch (c.op()) {
                    case EQ -> path + " = " + value;
                    case NE -> path + " <> " + value;
                    case LT -> path + " < " + value;
                    case LE -> path + " <= " + value;
                    case GT -> path + " > " + value;
                    case GE -> path + " >= " + value;
                    case IN -> path + " IN " + value;
                    case NOT_IN -> path + " NOT IN " + value;
                    case LIKE_PREFIX -> path + " STARTS WITH " + value;
                    case CONTAINS_CI -> path + " CONTAINS (ignoring case) " + value;
                    case IS_NULL -> path + " IS NULL";
                    case NOT_NULL -> path + " IS NOT NULL";
                    case BETWEEN -> c.operand() instanceof Operand.Literal(List<?> l) && l.size() == 2
                            ? path + " BETWEEN " + literal(l.get(0)) + " AND " + literal(l.get(1))
                            : path + " BETWEEN " + value;
                };
            }
        };
    }

    private static String literal(@Nullable Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof List<?> list) {
            return "(" + String.join(", ", list.stream().map(CriteriaQueryEngine::literal).toList()) + ")";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Parser state for one request. */
    private final class Parse {
        final AdhocScope scope;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Object> normalized = new LinkedHashMap<>();
        final Set<CatalogElementRef> references = new LinkedHashSet<>();
        @Nullable EffectiveEntity root;
        int conditions;

        Parse(AdhocScope scope) {
            this.scope = scope;
        }

        CriteriaCheck invalid() {
            return new CriteriaCheck(false, errors, warnings, "", normalized, null, 0, null);
        }

        /** A column reached from the root: its attribute and whether a to-many relation was crossed. */
        private record Resolved(EffectiveAttribute attribute, boolean toMany) {}

        @Nullable Resolved resolve(Object raw, String where, String purpose) {
            if (!(raw instanceof String text) || text.isBlank()) {
                errors.add(where + ": must be a column name such as \"status\" or \"customer.name\"");
                return null;
            }
            AttributePath path;
            try {
                path = AttributePath.parse(text.trim());
            } catch (IllegalArgumentException e) {
                errors.add(where + ": '" + text + "' is not a valid column path");
                return null;
            }
            EffectiveEntity current = Objects.requireNonNull(root);
            boolean toMany = false;
            for (String segment : path.joinPath()) {
                EffectiveEntity from = current;
                RelationDescriptor relation = from.relations().stream().filter(r -> r.name().equals(segment))
                        .findFirst().orElse(null);
                EffectiveEntity target = relation == null ? null
                        : scope.catalog().entity(relation.target()).filter(scope::sees).orElse(null);
                if (relation == null || target == null) {
                    errors.add(where + ": '" + segment + "' is not a relation of " + from.name() + " you can follow; "
                            + "relations: " + visibleRelations(from, scope).stream().map(RelationDescriptor::name)
                            .toList());
                    return null;
                }
                toMany |= relation.kind().toMany();
                references.add(target.ref());
                current = target;
            }
            EffectiveEntity owner = current;
            EffectiveAttribute attribute = owner.attribute(path.attributeName())
                    .filter(a -> a.exposable() && scope.principal().isCleared(a.classification()))
                    .orElse(null);
            if (attribute == null) {
                errors.add(where + ": " + owner.name() + " has no column '" + path.attributeName()
                        + "' you can use; columns: " + visibleAttributes(owner, scope).stream()
                        .map(EffectiveAttribute::name).toList());
                return null;
            }
            if (toMany && !purpose.equals("filter")) {
                errors.add(where + ": '" + text + "' crosses a to-many relation; it can only be used in where, "
                        + "not in " + purpose);
                return null;
            }
            references.add(attribute.ref());
            return new Resolved(attribute, toMany);
        }

        List<Projection> select(@Nullable Object raw) {
            EffectiveEntity r = Objects.requireNonNull(root);
            references.add(r.ref());
            List<Projection> out = new ArrayList<>();
            List<Object> normalizedSelect = new ArrayList<>();
            if (raw == null) {
                for (EffectiveAttribute a : visibleAttributes(r, scope)) {
                    if (out.size() == MAX_SELECT) {
                        break;
                    }
                    out.add(new Projection(AttributePath.of(a.name())));
                    normalizedSelect.add(a.name());
                    references.add(a.ref());
                }
                if (out.isEmpty()) {
                    errors.add("select: " + r.name() + " has no column you can read");
                }
            } else if (!(raw instanceof List<?> list) || list.isEmpty()) {
                errors.add("select: must be a non-empty list of column names");
            } else if (list.size() > MAX_SELECT) {
                errors.add("select: at most " + MAX_SELECT + " columns");
            } else {
                Set<String> names = new LinkedHashSet<>();
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    String at = "select[" + i + "]";
                    String path = item instanceof Map<?, ?> m ? (m.get("path") instanceof String s ? s : null)
                            : item instanceof String s ? s : null;
                    String alias = item instanceof Map<?, ?> m && m.get("as") instanceof String s && !s.isBlank()
                            ? s : null;
                    if (resolve(path, at, "select") == null) {
                        continue;
                    }
                    Projection projection = new Projection(AttributePath.parse(path.trim()), alias);
                    if (!names.add(projection.outputName())) {
                        errors.add(at + ": '" + projection.outputName() + "' is selected twice");
                        continue;
                    }
                    out.add(projection);
                    normalizedSelect.add(alias == null ? path.trim() : Map.of("path", path.trim(), "as", alias));
                }
            }
            normalized.put("select", normalizedSelect);
            return out;
        }

        @Nullable FilterNode condition(@Nullable Object raw, String at, int depth) {
            if (depth > MAX_NESTING) {
                errors.add(at + ": nested deeper than " + MAX_NESTING + " levels");
                return null;
            }
            if (!(raw instanceof Map<?, ?> map)) {
                errors.add(at + ": must be an object: {\"all\":[...]}, {\"any\":[...]}, {\"not\":{...}} or "
                        + "{\"path\":..., \"op\":..., \"value\":...}");
                return null;
            }
            for (String group : List.of("all", "and", "any", "or")) {
                if (map.containsKey(group)) {
                    if (map.size() != 1) {
                        errors.add(at + ": a \"" + group + "\" group cannot have other fields");
                        return null;
                    }
                    if (!(map.get(group) instanceof List<?> items) || items.isEmpty()) {
                        errors.add(at + "." + group + ": must be a non-empty list of conditions");
                        return null;
                    }
                    List<FilterNode> children = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        FilterNode child = condition(items.get(i), at + "." + group + "[" + i + "]", depth + 1);
                        if (child != null) {
                            children.add(child);
                        }
                    }
                    if (children.size() != items.size()) {
                        return null;
                    }
                    boolean and = group.equals("all") || group.equals("and");
                    return and ? new FilterNode.And(children) : new FilterNode.Or(children);
                }
            }
            if (map.containsKey("not")) {
                if (map.size() != 1) {
                    errors.add(at + ": a \"not\" group cannot have other fields");
                    return null;
                }
                FilterNode child = condition(map.get("not"), at + ".not", depth + 1);
                return child == null ? null : new FilterNode.Not(child);
            }
            return comparison(map, at);
        }

        @Nullable FilterNode comparison(Map<?, ?> map, String at) {
            conditions++;
            for (Object key : map.keySet()) {
                if (!Set.of("path", "op", "value", "principal").contains(String.valueOf(key))) {
                    errors.add(at + "." + key + ": unknown field; a condition has path, op and value (or principal)");
                    return null;
                }
            }
            Resolved column = resolve(map.get("path"), at + ".path", "filter");
            Operator op = null;
            if (!(map.get("op") instanceof String opText)) {
                errors.add(at + ".op: required, one of " + Arrays.toString(Operator.values()));
            } else {
                try {
                    op = Operator.valueOf(opText.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add(at + ".op: unknown operator '" + opText + "'; use one of "
                            + Arrays.toString(Operator.values()));
                }
            }
            if (column == null || op == null) {
                return null;
            }
            String path = String.valueOf(map.get("path")).trim();
            ValueType type = ValueType.of(column.attribute().descriptor().javaType());
            if (!type.operators.contains(op)) {
                errors.add(at + ".op: " + op + " does not apply to " + path + " (" + type.label + "); use one of "
                        + type.operators);
                return null;
            }
            boolean hasValue = map.containsKey("value");
            boolean hasPrincipal = map.containsKey("principal");
            if (op.isUnary()) {
                if (hasValue || hasPrincipal) {
                    errors.add(at + ": " + op + " takes no value");
                    return null;
                }
                return new FilterNode.Comparison(AttributePath.parse(path), op, new Operand.Literal(null));
            }
            if (hasValue == hasPrincipal) {
                errors.add(at + ": give exactly one of \"value\" or \"principal\"");
                return null;
            }
            if (hasPrincipal) {
                if (!(map.get("principal") instanceof String attr)
                        || !scope.principal().attributes().containsKey(attr)) {
                    errors.add(at + ".principal: must name one of your principal attributes "
                            + scope.principal().attributes().keySet().stream().sorted().toList());
                    return null;
                }
                if (op != Operator.EQ && op != Operator.NE && op != Operator.IN) {
                    errors.add(at + ".op: a principal value works with EQ, NE or IN");
                    return null;
                }
                return new FilterNode.Comparison(AttributePath.parse(path), op, new Operand.PrincipalAttr(attr));
            }
            Object value = map.get("value");
            if (op.isMultiValue()) {
                if (!(value instanceof List<?> list) || list.isEmpty()) {
                    errors.add(at + ".value: " + op + " needs a non-empty list"
                            + (op == Operator.BETWEEN ? " [low, high]" : ""));
                    return null;
                }
                if (op == Operator.BETWEEN && list.size() != 2) {
                    errors.add(at + ".value: BETWEEN needs exactly [low, high]");
                    return null;
                }
                if (list.size() > QueryValidator.MAX_IN_SIZE) {
                    errors.add(at + ".value: at most " + QueryValidator.MAX_IN_SIZE + " values");
                    return null;
                }
                List<Object> converted = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    Object one = type.convert(list.get(i), at + ".value[" + i + "]", errors);
                    if (one == null) {
                        return null;
                    }
                    converted.add(one);
                }
                return new FilterNode.Comparison(AttributePath.parse(path), op, new Operand.Literal(converted));
            }
            if (value == null) {
                errors.add(at + ".value: is null; use the IS_NULL or NOT_NULL operator instead");
                return null;
            }
            if (value instanceof List<?>) {
                errors.add(at + ".value: " + op + " takes one value; use IN for a list");
                return null;
            }
            if (op.isStringOnly() && value instanceof String s && (s.contains("%") || s.contains("_"))) {
                errors.add(at + ".value: write the plain text without % or _ wildcards; " + op + " adds them");
                return null;
            }
            Object converted = type.convert(value, at + ".value", errors);
            return converted == null ? null
                    : new FilterNode.Comparison(AttributePath.parse(path), op, new Operand.Literal(converted));
        }

        List<SortSpec> orderBy(@Nullable Object raw, List<Projection> select) {
            EffectiveEntity r = Objects.requireNonNull(root);
            List<SortSpec> out = new ArrayList<>();
            if (raw != null && !(raw instanceof List<?>)) {
                errors.add("orderBy: must be a list such as [{\"path\":\"createdAt\",\"direction\":\"desc\"}]");
            } else if (raw instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    String at = "orderBy[" + i + "]";
                    String path;
                    boolean desc = false;
                    if (item instanceof String s) {
                        path = s;
                    } else if (item instanceof Map<?, ?> m && m.get("path") instanceof String s) {
                        path = s;
                        Object direction = m.get("direction");
                        if (direction != null && !(direction instanceof String d
                                && (d.equalsIgnoreCase("asc") || d.equalsIgnoreCase("desc")))) {
                            errors.add(at + ".direction: \"asc\" or \"desc\"");
                            continue;
                        }
                        desc = direction instanceof String d && d.equalsIgnoreCase("desc")
                                || Boolean.TRUE.equals(m.get("desc"));
                    } else {
                        errors.add(at + ": must be a column name or {\"path\":...,\"direction\":\"asc|desc\"}");
                        continue;
                    }
                    if (resolve(path, at, "orderBy") != null) {
                        out.add(new SortSpec(AttributePath.parse(path.trim()), desc));
                    }
                }
            }
            // stable pages: end with the identifier (keyset pagination needs a unique tail)
            List<EffectiveAttribute> ids = visibleAttributes(r, scope).stream()
                    .filter(a -> a.descriptor().identifier()).toList();
            for (EffectiveAttribute id : ids) {
                boolean present = out.stream().anyMatch(s -> s.path().segments().equals(List.of(id.name())));
                if (!present) {
                    out.add(SortSpec.asc(AttributePath.of(id.name())));
                    references.add(id.ref());
                    if (raw != null) {
                        warnings.add("orderBy: added " + id.name() + " as the last sort column so pages are stable");
                    }
                }
            }
            if (out.isEmpty() && !select.isEmpty() && select.getFirst().path().joinDepth() == 0) {
                out.add(SortSpec.asc(select.getFirst().path()));
                warnings.add("orderBy: " + r.name() + " has no visible identifier; sorted by "
                        + select.getFirst().path() + ", pages may overlap");
            }
            List<Object> normalizedOrder = new ArrayList<>();
            out.forEach(s -> normalizedOrder.add(Map.of("path", s.path().toString(),
                    "direction", s.descending() ? "desc" : "asc")));
            normalized.put("orderBy", normalizedOrder);
            return out;
        }

        int pageSize(@Nullable Object raw) {
            int cap = pageCap(Objects.requireNonNull(root), scope);
            int size = Math.min(DEFAULT_PAGE_SIZE, cap);
            if (raw != null) {
                if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1) {
                    errors.add("limit: a whole number of at least 1");
                } else if (n.intValue() > cap) {
                    size = cap;
                    warnings.add("limit: reduced from " + n.intValue() + " to " + cap
                            + " (the most one page can hold); use nextCursor for more");
                } else {
                    size = n.intValue();
                }
            }
            normalized.put("limit", size);
            return size;
        }

        void mandatoryFilters(@Nullable FilterNode where) {
            EffectiveEntity r = Objects.requireNonNull(root);
            for (String column : r.mandatoryFilters()) {
                if (where == null || !boundToPrincipal(where, column)) {
                    errors.add("where: " + r.name() + " must be filtered on '" + column + "' with your own identity: "
                            + "add {\"path\":\"" + column + "\",\"op\":\"EQ\",\"principal\":\"<one of "
                            + scope.principal().attributes().keySet().stream().sorted().toList()
                            + ">\"} to the top-level \"all\" group");
                }
            }
        }

        private boolean boundToPrincipal(FilterNode node, String column) {
            return switch (node) {
                case FilterNode.And(var children) -> children.stream().anyMatch(c -> boundToPrincipal(c, column));
                case FilterNode.Comparison c -> (c.op() == Operator.EQ || c.op() == Operator.IN)
                        && c.path().segments().equals(List.of(column))
                        && c.operand() instanceof Operand.PrincipalAttr;
                default -> false;
            };
        }
    }

    // ── value types ─────────────────────────────────────────────────────────────────────────────────────────────

    /** How a column's Java type is described to the model and how JSON values are converted to it. */
    private record ValueType(String label, Set<Operator> operators, @Nullable List<String> enumValues,
                             @Nullable Class<?> javaClass) {

        static ValueType of(String javaType) {
            return switch (javaType) {
                case "java.lang.String", "String", "char", "java.lang.Character" ->
                        new ValueType("string", TEXT, null, String.class);
                case "int", "java.lang.Integer", "long", "java.lang.Long", "short", "java.lang.Short", "byte",
                     "java.lang.Byte", "java.math.BigInteger" -> new ValueType("integer", ORDERED, null,
                        boxed(javaType));
                case "double", "java.lang.Double", "float", "java.lang.Float", "java.math.BigDecimal" ->
                        new ValueType("number", ORDERED, null, boxed(javaType));
                case "boolean", "java.lang.Boolean" -> new ValueType("boolean", BOOL, null, Boolean.class);
                case "java.time.LocalDate" -> new ValueType("date (YYYY-MM-DD)", ORDERED, null, LocalDate.class);
                case "java.time.LocalDateTime" ->
                        new ValueType("date-time (YYYY-MM-DDThh:mm:ss)", ORDERED, null, LocalDateTime.class);
                case "java.time.LocalTime" -> new ValueType("time (hh:mm:ss)", ORDERED, null, LocalTime.class);
                case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                        new ValueType("timestamp (ISO-8601 with zone, e.g. 2026-01-31T10:00:00Z)", ORDERED, null,
                                boxed(javaType));
                case "java.util.UUID" -> new ValueType("uuid", EQUALITY, null, UUID.class);
                default -> {
                    Class<?> type = load(javaType);
                    if (type != null && type.isEnum()) {
                        List<String> values = Arrays.stream(type.getEnumConstants())
                                .map(c -> ((Enum<?>) c).name()).toList();
                        yield new ValueType("enum", EQUALITY, values, type);
                    }
                    yield new ValueType("other", BOOL, null, null);
                }
            };
        }

        private static @Nullable Class<?> boxed(String javaType) {
            return switch (javaType) {
                case "int", "java.lang.Integer" -> Integer.class;
                case "long", "java.lang.Long" -> Long.class;
                case "short", "java.lang.Short" -> Short.class;
                case "byte", "java.lang.Byte" -> Byte.class;
                case "double", "java.lang.Double" -> Double.class;
                case "float", "java.lang.Float" -> Float.class;
                default -> load(javaType);
            };
        }

        private static @Nullable Class<?> load(String javaType) {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                return Class.forName(javaType, false, cl != null ? cl : CriteriaQueryEngine.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        /** The JSON value converted to the column's Java type, or {@code null} with an error added. */
        @Nullable Object convert(@Nullable Object value, String at, List<String> errors) {
            if (value == null) {
                errors.add(at + ": must not be null");
                return null;
            }
            try {
                Object converted = convertOrThrow(value);
                if (converted != null) {
                    return converted;
                }
            } catch (ArithmeticException | NumberFormatException | DateTimeParseException e) {
                // fall through to the message
            } catch (IllegalArgumentException e) {
                if (enumValues != null) {
                    errors.add(at + ": '" + value + "' is not one of " + enumValues);
                    return null;
                }
            }
            errors.add(at + ": '" + value + "' is not a valid " + label);
            return null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private @Nullable Object convertOrThrow(Object value) {
            if (javaClass == null) {
                return value instanceof String || value instanceof Number || value instanceof Boolean ? value : null;
            }
            if (javaClass == String.class) {
                return value instanceof String s ? s : value instanceof Number || value instanceof Boolean
                        ? value.toString() : null;
            }
            if (javaClass == Boolean.class) {
                if (value instanceof Boolean b) {
                    return b;
                }
                return value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))
                        ? Boolean.valueOf(s) : null;
            }
            if (Number.class.isAssignableFrom(javaClass)) {
                BigDecimal d = value instanceof Number n ? new BigDecimal(n.toString())
                        : value instanceof String s ? new BigDecimal(s.trim()) : null;
                if (d == null) {
                    return null;
                }
                if (javaClass == Integer.class) return d.intValueExact();
                if (javaClass == Long.class) return d.longValueExact();
                if (javaClass == Short.class) return d.shortValueExact();
                if (javaClass == Byte.class) return d.byteValueExact();
                if (javaClass == BigInteger.class) return d.toBigIntegerExact();
                if (javaClass == Double.class) return d.doubleValue();
                if (javaClass == Float.class) return d.floatValue();
                return d;
            }
            if (!(value instanceof String s)) {
                return null;
            }
            String text = s.trim();
            if (javaClass == LocalDate.class) return LocalDate.parse(text);
            if (javaClass == LocalDateTime.class) return LocalDateTime.parse(text);
            if (javaClass == LocalTime.class) return LocalTime.parse(text);
            if (javaClass == Instant.class) return OffsetDateTime.parse(text).toInstant();
            if (javaClass == OffsetDateTime.class) return OffsetDateTime.parse(text);
            if (javaClass == ZonedDateTime.class) return ZonedDateTime.parse(text);
            if (javaClass == UUID.class) return UUID.fromString(text);
            if (javaClass.isEnum()) {
                for (Object constant : javaClass.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                        return constant;
                    }
                }
                throw new IllegalArgumentException("not a constant");
            }
            return null;
        }
    }
}
