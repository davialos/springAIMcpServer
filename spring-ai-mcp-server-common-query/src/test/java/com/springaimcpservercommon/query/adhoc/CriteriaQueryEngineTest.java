package com.springaimcpservercommon.query.adhoc;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.query.ast.FilterNode;
import com.springaimcpservercommon.query.ast.Operand;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.criteria.CriteriaQueryExecutor;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriteriaQueryEngineTest {

    enum Status { OPEN, SHIPPED, CANCELLED }

    private static final String ORDER = "com.acme.Order";
    private static final String CUSTOMER = "com.acme.Customer";
    private static final String LINE = "com.acme.OrderLine";
    private static final String TICKET = "com.acme.Ticket";
    private static final String SECRET = "com.acme.Secret";
    private static final UUID WS = UUID.randomUUID();

    private final CriteriaQueryEngine engine = new CriteriaQueryEngine();

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

    private record Col(String name, String type, boolean sensitive, Classification classification, boolean id) {
        static Col of(String name, String type) {
            return new Col(name, type, false, Classification.INTERNAL, false);
        }
    }

    private static EffectiveEntity entity(String type, String name, Classification classification, int maxLimit,
                                          List<String> mandatory, List<RelationDescriptor> relations, Col... cols) {
        List<AttributeDescriptor> descriptors = new ArrayList<>();
        Map<CatalogElementRef, EffectiveAttribute> attributes = new LinkedHashMap<>();
        for (Col c : cols) {
            CatalogElementRef ref = AttributeDescriptor.refOf(type, c.name());
            AttributeDescriptor d = new AttributeDescriptor(ref, c.name(), c.type(), "the " + c.name(), c.sensitive(),
                    false, c.classification(), c.id());
            descriptors.add(d);
            attributes.put(ref, new EffectiveAttribute(ref, d, d.meaning(), c.sensitive(), c.classification(), true,
                    List.of()));
        }
        CatalogElementRef ref = CatalogElementRef.entity(type);
        EntityDescriptor descriptor = new EntityDescriptor(ref, type, name, "All " + name + "s", List.of(),
                classification, maxLimit, mandatory, descriptors, relations, null);
        return new EffectiveEntity(ref, descriptor, descriptor.description(), List.of(), classification, maxLimit,
                mandatory, true, attributes, List.of());
    }

    private static EffectiveCatalog catalog() {
        var order = entity(ORDER, "Order", Classification.INTERNAL, 50, List.of(), List.of(
                        new RelationDescriptor("customer", RelationDescriptor.Kind.MANY_TO_ONE,
                                CatalogElementRef.entity(CUSTOMER), null, false),
                        new RelationDescriptor("lines", RelationDescriptor.Kind.ONE_TO_MANY,
                                CatalogElementRef.entity(LINE), "order", true)),
                new Col("id", "java.lang.Long", false, Classification.INTERNAL, true),
                Col.of("status", Status.class.getName()),
                Col.of("total", "java.math.BigDecimal"),
                Col.of("placedOn", "java.time.LocalDate"),
                Col.of("note", "java.lang.String"),
                new Col("cardNumber", "java.lang.String", true, Classification.INTERNAL, false),
                new Col("fraudScore", "java.lang.Integer", false, Classification.RESTRICTED, false));
        var customer = entity(CUSTOMER, "Customer", Classification.INTERNAL, 100, List.of(), List.of(),
                new Col("id", "java.lang.Long", false, Classification.INTERNAL, true),
                Col.of("name", "java.lang.String"),
                new Col("email", "java.lang.String", true, Classification.INTERNAL, false));
        var line = entity(LINE, "OrderLine", Classification.INTERNAL, 100, List.of(), List.of(),
                new Col("id", "java.lang.Long", false, Classification.INTERNAL, true),
                Col.of("sku", "java.lang.String"));
        var ticket = entity(TICKET, "Ticket", Classification.INTERNAL, 100, List.of("tenantId"), List.of(),
                new Col("id", "java.util.UUID", false, Classification.INTERNAL, true),
                Col.of("tenantId", "java.lang.String"),
                Col.of("open", "java.lang.Boolean"));
        var secret = entity(SECRET, "Secret", Classification.RESTRICTED, 100, List.of(), List.of(),
                new Col("id", "java.lang.Long", false, Classification.RESTRICTED, true));
        Map<CatalogElementRef, EffectiveEntity> entities = new LinkedHashMap<>();
        for (EffectiveEntity e : List.of(order, customer, line, ticket, secret)) {
            entities.put(e.ref(), e);
        }
        return new EffectiveCatalog(3, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), entities, Map.of(),
                List.of(), List.of());
    }

    private static DaiPrincipal principal() {
        return new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "https://idp", "alice", "Alice", Set.of(),
                Set.of(), Map.of(), Map.of("tenantId", "t-1"), Classification.CONFIDENTIAL, null, Set.of());
    }

    private static AdhocScope scope(Set<String> allowed, int maxRows) {
        return new AdhocScope(catalog(), principal(), WS, allowed, maxRows);
    }

    private static AdhocScope scope() {
        return scope(Set.of(), 100);
    }

    private static Map<String, Object> cmp(String path, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    // ── describe ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void describeListsOnlyWhatTheCallerMayQuery() {
        Map<String, Object> summary = engine.describe(scope(), null);
        assertThat(summary.toString()).contains("Order", "Customer", "Ticket").doesNotContain("Secret")
                .doesNotContain("cardNumber").doesNotContain("fraudScore").doesNotContain("t-1");
        assertThat(summary.get("principalAttributes")).isEqualTo(List.of("tenantId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) engine.describe(scope(), "order").get("entity");
        assertThat(order.toString()).contains("values=[OPEN, SHIPPED, CANCELLED]")
                .contains("date (YYYY-MM-DD)").contains("identifier=true").contains("filter only")
                .doesNotContain("email");
        assertThat(engine.describe(scope(Set.of("Order"), 10), null).toString()).doesNotContain("Ticket");
        assertThatThrownBy(() -> engine.describe(scope(), "Secret")).hasMessageContaining("available");
    }

    // ── check ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aWellFormedRequestCompilesWithTypedValuesStableSortAndExplain() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("entity", "Order");
        request.put("select", List.of("id", "status", Map.of("path", "customer.name", "as", "customer")));
        request.put("where", Map.of("all", List.of(
                cmp("status", "in", List.of("open", "SHIPPED")),
                Map.of("any", List.of(cmp("total", "GT", 100), cmp("placedOn", "BETWEEN",
                        List.of("2026-01-01", "2026-03-31")))),
                Map.of("not", Map.of("path", "note", "op", "IS_NULL")),
                cmp("lines.sku", "LIKE_PREFIX", "AB"))));
        request.put("orderBy", List.of(Map.of("path", "placedOn", "direction", "desc")));
        request.put("limit", 500);

        CriteriaCheck check = engine.check(request, scope(Set.of(), 30));

        assertThat(check.errors()).isEmpty();
        assertThat(check.valid()).isTrue();
        assertThat(check.pageSize()).isEqualTo(30);
        assertThat(check.warnings()).anyMatch(w -> w.contains("reduced from 500 to 30"))
                .anyMatch(w -> w.contains("added id"));
        assertThat(check.explain()).isEqualTo("SELECT id, status, customer.name FROM Order WHERE (status IN ('OPEN', "
                + "'SHIPPED') AND (total > 100 OR placedOn BETWEEN '2026-01-01' AND '2026-03-31') AND NOT note IS NULL "
                + "AND lines.sku STARTS WITH 'AB') ORDER BY placedOn DESC, id ASC LIMIT 30");
        QueryDefinition q = check.query();
        assertThat(q.root()).isEqualTo(CatalogElementRef.entity(ORDER));
        assertThat(q.catalogHash()).isEqualTo(catalog().policyFingerprint());
        var and = (FilterNode.And) q.where();
        var in = (FilterNode.Comparison) and.children().getFirst();
        assertThat(((Operand.Literal) in.operand()).value()).isEqualTo(List.of(Status.OPEN, Status.SHIPPED));
        var between = (FilterNode.Comparison) ((FilterNode.Or) and.children().get(1)).children().get(1);
        assertThat(((Operand.Literal) between.operand()).value())
                .isEqualTo(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        var gt = (FilterNode.Comparison) ((FilterNode.Or) and.children().get(1)).children().getFirst();
        assertThat(((Operand.Literal) gt.operand()).value()).isEqualTo(new BigDecimal("100"));
        // the same request gives the same query id, so a cursor of one page works for the next
        assertThat(engine.check(request, scope(Set.of(), 30)).query().id()).isEqualTo(q.id());
    }

    @Test
    void defaultsSelectEveryVisibleColumnAndSortByTheIdentifier() {
        CriteriaCheck check = engine.check(Map.of("entity", "Customer"), scope());
        assertThat(check.valid()).isTrue();
        assertThat(check.explain()).isEqualTo("SELECT id, name FROM Customer ORDER BY id ASC LIMIT 20");
        assertThat(check.normalized()).containsEntry("limit", 20).containsEntry("select", List.of("id", "name"));
        assertThat(check.warnings()).isEmpty();
    }

    @Test
    void mistakesComeBackAsErrorsAModelCanAct() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("entity", "Order");
        request.put("select", List.of("id", "cardNumber", "fraudScore", "customer.email", "lines.sku", "nope.x"));
        request.put("where", Map.of("all", List.of(
                cmp("status", "EQUALS", "OPEN"),
                cmp("status", "EQ", "LOST"),
                cmp("total", "CONTAINS_CI", "1"),
                cmp("placedOn", "GT", "yesterday"),
                cmp("note", "EQ", List.of("a")),
                Map.of("path", "note", "op", "EQ"),
                cmp("note", "CONTAINS_CI", "50%"),
                Map.of("path", "note", "op", "EQ", "principal", "salary"))));
        request.put("orderBy", List.of("lines.sku"));
        request.put("limit", 0);
        request.put("drop", true);

        CriteriaCheck check = engine.check(request, scope());

        assertThat(check.valid()).isFalse();
        assertThat(check.query()).isNull();
        assertThat(String.join("\n", check.errors())).contains(
                "drop: unknown field",
                "select[1]: Order has no column 'cardNumber'",
                "select[2]: Order has no column 'fraudScore'",
                "select[3]: Customer has no column 'email'",
                "select[4]: 'lines.sku' crosses a to-many relation",
                "select[5]: 'nope' is not a relation of Order",
                "where.all[0].op: unknown operator 'EQUALS'",
                "where.all[1].value: 'LOST' is not one of [OPEN, SHIPPED, CANCELLED]",
                "where.all[2].op: CONTAINS_CI does not apply to total (number)",
                "where.all[3].value: 'yesterday' is not a valid date (YYYY-MM-DD)",
                "where.all[4].value: EQ takes one value; use IN for a list",
                "where.all[5]: give exactly one of \"value\" or \"principal\"",
                "where.all[6].value: write the plain text without % or _ wildcards",
                "where.all[7].principal: must name one of your principal attributes [tenantId]",
                "orderBy[0]: 'lines.sku' crosses a to-many relation",
                "limit: a whole number of at least 1");
    }

    @Test
    void hiddenEntitiesAndTheBindingsAllowListAreEnforced() {
        assertThat(engine.check(Map.of("entity", "Secret"), scope()).errors()).singleElement().asString()
                .contains("no entity 'Secret'");
        CriteriaCheck outside = engine.check(Map.of("entity", "Order", "select", List.of("customer.name")),
                scope(Set.of("Order"), 10));
        assertThat(outside.errors()).singleElement().asString().contains("'customer' is not a relation");
        assertThat(engine.check(Map.of("entity", "com.acme.Order"), scope(Set.of("entity:com.acme.Order"), 10))
                .valid()).isTrue();
    }

    @Test
    void mandatoryFiltersMustBeBoundToTheCallersOwnIdentity() {
        CriteriaCheck literal = engine.check(Map.of("entity", "Ticket", "where", cmp("tenantId", "EQ", "t-2")),
                scope());
        assertThat(literal.valid()).isFalse();
        assertThat(literal.errors()).anyMatch(e -> e.contains("must be filtered on 'tenantId' with your own identity"));
        CriteriaCheck inOr = engine.check(Map.of("entity", "Ticket", "where", Map.of("any", List.of(
                Map.of("path", "tenantId", "op", "EQ", "principal", "tenantId"), cmp("open", "EQ", true)))), scope());
        assertThat(inOr.valid()).isFalse();

        CriteriaCheck bound = engine.check(Map.of("entity", "Ticket", "where", Map.of("all", List.of(
                Map.of("path", "tenantId", "op", "EQ", "principal", "tenantId"), cmp("open", "EQ", "true")))),
                scope());
        assertThat(bound.errors()).isEmpty();
        assertThat(bound.explain()).contains("tenantId = <your tenantId>").contains("open = true");
    }

    @Test
    void sizeLimitsAreEnforced() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < CriteriaQueryEngine.MAX_CONDITIONS + 1; i++) {
            many.add(cmp("total", "GT", i));
        }
        assertThat(engine.check(Map.of("entity", "Order", "where", Map.of("all", many)), scope()).errors())
                .anyMatch(e -> e.contains("at most " + CriteriaQueryEngine.MAX_CONDITIONS + " conditions"));
        Object deep = cmp("total", "GT", 1);
        for (int i = 0; i < CriteriaQueryEngine.MAX_NESTING + 1; i++) {
            deep = Map.of("not", deep);
        }
        assertThat(engine.check(Map.of("entity", "Order", "where", deep), scope()).errors())
                .anyMatch(e -> e.contains("nested deeper"));
    }

    // ── execute ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void executeRunsTheCheckedQueryAsTheCallerWithItsPageSizeAndCursor() {
        AtomicReference<Map<String, Object>> params = new AtomicReference<>();
        AtomicReference<Integer> size = new AtomicReference<>();
        QueryExecutor executor = (query, principal, p, policies, catalog, requested) -> {
            params.set(p);
            size.set(requested);
            assertThat(principal.subjectId()).isEqualTo("alice");
            return QueryResult.paged(List.of(Map.of("id", 1L)), "next");
        };
        CriteriaCheck check = engine.check(Map.of("entity", "Order", "limit", 5, "cursor", "abc"), scope());

        QueryResult result = engine.execute(check, scope(), executor);

        assertThat(result.nextCursor()).isEqualTo("next");
        assertThat(params.get()).containsEntry(CriteriaQueryExecutor.CURSOR_PARAM, "abc");
        assertThat(size.get()).isEqualTo(5);
        CriteriaCheck invalid = engine.check(Map.of("entity", "Nope"), scope());
        assertThatThrownBy(() -> engine.execute(invalid, scope(), executor))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
