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
