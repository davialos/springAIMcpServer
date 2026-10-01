# LLD-05: Dynamic Query Engine

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | dynamic-runtime-designer |
| Module(s) | `core` (AST, validator), `jpa` (Criteria compiler, executor) |
| Related features | F-30 … F-35 |
| Related ADRs | ADR-0004 |

## 1. Purpose & responsibilities
Execute admin-defined read queries over exposed JPA entities **without** repository
interfaces and **without** accepting query text. Enforce row-level and field-level security.

## 2. Query AST (stored as JSON; also the builder UI's model)
```java
// design sketch
public record QueryDefinition(ResourceId id, int revision, WorkspaceId workspace,
        CatalogElementRef root,                 // entity:com.acme.Order
        List<Projection> select,                // attribute paths, e.g. "customer.name"
        FilterNode where,                       // tree
        List<SortSpec> orderBy,
        PageSpec page,                          // default & max size, keyset option
        List<QueryParam> params,                // declared inputs with JSON schema
        Set<CatalogElementRef> references, String catalogHash) {}
sealed interface FilterNode permits And, Or, Not, Comparison {}
record Comparison(AttributePath path, Operator op, Operand value) implements FilterNode {}
enum Operator { EQ, NE, LT, LE, GT, GE, IN, NOT_IN, LIKE_PREFIX, CONTAINS_CI, IS_NULL, NOT_NULL, BETWEEN }
sealed interface Operand permits ParamRef, Literal, PrincipalAttr {}  // PrincipalAttr = "principal.tenantId"
```
v1.x adds `Aggregation(fn, path)` and `groupBy`.

## 3. Validation (publish-time AND runtime)
| Rule | Why |
|------|-----|
| Every path segment exists in the catalog, is exposed (`@AiEntityProperty`), not sensitive | Allow-list |
| Joins only along declared relations; max depth 3 | Prevent cartesian blow-ups |
| Operator compatible with attribute JSON type | Type safety |
| `LIKE` only prefix / contains with escaped wildcards | No user-supplied patterns with `%` injection |
| Max `IN` list size (default 500) | Plan & memory safety |
| Classification of any selected attribute ≤ author clearance at publish, and ≤ caller clearance at runtime (else masked) | Data protection |
| Page size ≤ max; ORDER BY required for pagination (stable) | Deterministic paging |

## 4. Compilation → Criteria API
```
AST ─► CriteriaBuilder cb; CriteriaQuery<Tuple> q; Root<?> r = q.from(entityClass)
     joins: cache by path, JoinType.LEFT for optional projections, INNER for filters
     where: FilterNode → Predicate (params bound via cb.parameter(type, name))
     rowPolicies: AND(policyPredicates(principal))           ◄── always appended, not optional
     select: q.multiselect(paths).distinct(needed?)
     order/page: orderBy + setFirstResult/setMaxResults (or keyset predicate)
TypedQuery: setHint("jakarta.persistence.query.timeout", ms), setHint(READ_ONLY), FlushMode.COMMIT
```
- Executed in `@Transactional(readOnly = true)` with **the host's** `EntityManager` (or a
  configured read-replica `EntityManagerFactory`, `dynamic.ai.agent.query.entity-manager-factory`).
- Results as `Tuple` → `Map<String,Object>` → JSON via masking serializer. No entity
  objects leave the executor (prevents lazy-load explosions & accidental serialization).
- Compiled plan cache keyed by (queryId, revision, policyFingerprint).

## 5. Row-level security (F-34)
```java
// design sketch
public record RowPolicy(ResourceId id, CatalogElementRef entity, FilterNode predicate,   // may use PrincipalAttr
                        Set<RoleRef> appliesTo, Set<RoleRef> exemptRoles) {}
```
Examples: `Order.tenantId EQ principal.tenantId`; `Order.region IN principal.regions`.
Missing principal attribute ⇒ predicate evaluates to FALSE (fail closed). Policies are
also applied when an agent calls a query-backed tool. Host-native alternatives (Hibernate
`@Filter`, DB RLS like PostgreSQL policies) can be enabled instead via `RowSecurityStrategy` SPI.

## 5a. Entity query constraints (`@AiQueryConstraints`, LLD-02 §2)
Applied to **every** dynamic query (AI-originated and endpoint-originated), at publish time and at runtime.
- **Row cap:** effective limit = min(global `query.max-rows`, entity `maxLimit` after policy merge, query page max,
  caller-requested size). Our compiler sets `setMaxResults(effective)` itself; requests for more are capped, not failed,
  and the response says `truncated: true` so the model knows data is partial.
- **Mandatory filters:** every attribute in `mandatoryFilters` (e.g. `tenantId`) must be constrained by an `EQ`/`IN`
  predicate whose value is **bound server-side** — a `PrincipalAttr` operand, an applicable `RowPolicy`, or a request
  parameter pinned by an `argConstraint` (LLD-07 §2). A value supplied only by the model does **not** satisfy it (the model
  could pick another tenant). Missing at publish → 422; missing at runtime (e.g. an expired policy) → query refused.
- Constraints are part of the effective catalog (LLD-03 §4.1): `maxLimit` = min across layers, `mandatoryFilters` = union.
- **Pagination:** keyset by default (offset only for small tables); fetch `limit + 1` to compute `hasMore`; `nextCursor` is
  opaque and HMAC-signed, bound to query, filters, principal and snapshot generation (LLD-14 §3.3).

## 6. Preview sandbox (F-32)
Runs as the author, `LIMIT 20`, timeout 3 s, against the `preview` EMF if configured
(recommended: non-prod replica). Returns rows + generated SQL (for authors with
`perm:query:explain`) via Hibernate `StatementInspector` capture — never exposed to consumers.

## 7. Failure modes
| Failure | Behavior |
|---------|----------|
| Timeout | `QueryTimeoutException` → 504 problem `query-timeout` |
| Pool exhaustion | Bulkhead semaphore (`max-concurrent`) rejects fast → 503 |
| Entity renamed (drift) | Query suspended (LLD-03) |
| Too many rows | Hard cap even if page not requested (`max-rows`) |

## 8. Security
- No string concatenation anywhere in compilation; fuzz tests with injection payloads in all params.
- Writes impossible by construction (only `CriteriaQuery`, never `CriteriaUpdate/Delete`). The query
  engine is reused read-only to build proposal before-snapshots; all writes go through LLD-11.
- Named native SQL (v2, F-35): separate module, read-only DB user, four-eyes approval.

## 9. Configuration
| Property | Default |
|----------|---------|
| `dynamic.ai.agent.query.max-page-size` | `200` |
| `dynamic.ai.agent.query.max-rows` | `1000` |
| `dynamic.ai.agent.query.timeout` | `5s` |
| `dynamic.ai.agent.query.max-join-depth` | `3` |
| `dynamic.ai.agent.query.max-concurrent` | `20` (per node) |
| `dynamic.ai.agent.query.row-security` | `criteria` (`hibernate-filter`, `database`, `none` — `none` refused if any RESTRICTED entity exposed) |

## 10. Observability
Timer `dynamic.ai.agent.query.executions{query,outcome}`, histogram rows returned, span
`dai.query` (no bind values in spans by default).

## 11. Test strategy
Testcontainers PostgreSQL + MySQL; property-based tests (AST generator → compile → run →
never throws SQL grammar errors, row policy always applied); injection corpus; explain-plan snapshots for sample queries.

## 12. Model-built criteria queries (F-36)

Published queries are authored by a person and reviewed; F-36 lets an **agent build its own read query** from a user's
question, over the same AST, validator and Criteria executor. It is offered as three tools, bound like every other
tool (a `TOOL_BINDING` with `"source": {"kind": "criteria", ...}`, LLD-07 §2), so grants, kill switches, the per-turn
call limit, the read-only scope (ADR-0014), recording and MCP exposure apply unchanged.

| Tool (default name) | `source.tool` | What it does |
|---|---|---|
| `describe_data_model` | `describe` | Entities the caller may query; for one entity its columns (type, meaning, operators, enum values, identifier), relations (to-many marked "filter only") and mandatory filters, plus the *names* of the caller's principal attributes |
| `check_data_query` | `validate` | Checks a request and returns it explained in SQL-like form with the defaults filled in, or every problem with its location (`where.all[1].op: unknown operator 'EQUALS'; use one of …`). Reads no data |
| `run_data_query` | `execute` | Checks, then runs as the caller; one page of rows with `hasMore`/`nextCursor`, the explained query in `applied.filters.query`, `_context` per row (`@AiRowContext`) |

**Request format** (`query.adhoc.CriteriaQueryEngine`):

```json
{"entity": "Order",
 "select": ["id", "status", "customer.name", {"path": "total", "as": "amount"}],
 "where": {"all": [{"path": "customerId", "op": "EQ", "principal": "customerId"},
                   {"path": "status", "op": "IN", "value": ["OPEN", "SHIPPED"]},
                   {"any": [{"path": "total", "op": "GT", "value": 100},
                            {"not": {"path": "notes", "op": "IS_NULL"}}]}]},
 "orderBy": [{"path": "placedOn", "direction": "desc"}],
 "limit": 20, "cursor": "<nextCursor>"}
```

**Rules — stricter than for authored queries, because nobody reviewed the request:**

1. Only entities the catalog exposes (enabled, not above the caller's clearance) and allowed by the binding's
   `entities` list; only attributes that are enabled, **not sensitive** and not above the caller's clearance — for
   select, filter *and* sort; relations only to such entities, at most `max-join-depth` hops.
2. Select and sort cannot cross a to-many relation; a filter can (the query becomes `DISTINCT`).
3. Values are JSON literals **converted to the attribute's Java type** (numbers exact, `LocalDate`/`Instant`/… ISO-8601,
   UUID, enum by name case-insensitively) or `"principal": "<attr>"` — the caller's own attribute, bound server-side;
   nothing can bind another user's value. `null` is refused (use `IS_NULL`); `LIKE_PREFIX`/`CONTAINS_CI` take plain text.
4. Operators are restricted by type (strings: comparisons, IN, LIKE_PREFIX, CONTAINS_CI; numbers and dates:
   comparisons, IN, BETWEEN; booleans, UUIDs, enums: equality, IN).
5. An entity's **mandatory filters** (`@AiQueryConstraints`) must be bound to a principal attribute with EQ/IN in the
   top-level `all` group; a literal never satisfies them.
6. Limits: 30 columns, 40 comparisons, nesting 6, page size `min(binding maxRows (default 50), entity maxLimit,
   max-page-size)` — a larger `limit` is reduced with a warning. The identifier is appended to the sort so keyset
   pages are stable; the query id is derived from the request, so a cursor continues the same query.
7. The compiled `QueryDefinition` then passes `QueryValidator.validateAtPublish` (with the caller as author) and
   `validateAtRuntime` (catalog fingerprint, mandatory filters, clearance) before it may run; execution uses the
   `QueryExecutor` bean (bulkhead, timeout, read-only transaction).

Failures the model sees: `invalid_query` (problems in `hints`), `unknown_entity`, `query_timeout`,
`temporarily_unavailable` (bulkhead), `not_permitted` (no grant). Host exception text never reaches the model.
`dynamic.ai.agent.query.ai-criteria=false` disables every criteria binding. Row policies are not applied to *any*
query path yet (§5, same as published queries); mandatory filters are the enforced tenant boundary until they are.
Aggregations (F-33) are not offered to models yet.

