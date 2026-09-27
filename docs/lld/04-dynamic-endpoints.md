# LLD-04: Dynamic Endpoint Engine

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | dynamic-runtime-designer |
| Module(s) | `core` (model, pipeline), `webmvc` (registrar, handler) |
| Related features | F-20 … F-27, F-73 |
| Related ADRs | ADR-0006 |

## 1. Purpose & responsibilities
Turn published `EndpointDefinition` revisions into live Spring MVC routes under
`/dynamic-ai/api/**`, and execute them through a uniform, policy-enforcing pipeline.
Does not own query compilation (LLD-05) or agent execution (LLD-06).

## 2. Data model
```java
// design sketch
public record EndpointDefinition(ResourceId id, int revision, WorkspaceId workspace,
        String path,                 // relative, e.g. "/sales/orders/{customerId}"; validated pattern
        HttpMethod method,           // GET, POST (v1); PUT/PATCH/DELETE only for write endpoints (v2)
        String apiVersion,           // "v1"
        List<ParamSpec> params,      // name, in (PATH|QUERY|HEADER|BODY), JSON schema, required
        JsonSchema requestBody,      // POST only
        Backing backing,             // sealed: QueryBacking | AgentBacking | OperationBacking
        ResponseShape response,      // projection, renames, envelope, masking
        RateLimitSpec rateLimit, CacheSpec cache, Duration timeout,
        Set<CatalogElementRef> references, String catalogHash) {}
sealed interface Backing permits QueryBacking, AgentBacking, OperationBacking {}
record QueryBacking(ResourceId query, Map<String, String> paramBindings) implements Backing {}
record AgentBacking(ResourceId agent, String inputTemplate) implements Backing {}
record OperationBacking(CatalogElementRef operation, Map<String, String> paramBindings) implements Backing {}
```
Full URL = `{base-path}/api/{workspaceSlug}/{apiVersion}{path}` → workspace prefix makes
cross-team collisions impossible by construction.

## 3. Registration
```
SnapshotApplier(g+1)
 ├─ diff(current routes, g+1 routes) → toAdd, toRemove, toReplace
 ├─ validate all toAdd: pattern parse (PathPatternParser), no collision with
 │    - host mappings (handlerMapping.getHandlerMethods() keys ∩ our path space)
 │    - other dynamic routes (same method+pattern after normalization)
 ├─ for each: RequestMappingInfo.paths(full).methods(m).produces(json)
 │            .options(handlerMapping.getBuilderConfiguration()).build()
 │   handlerMapping.registerMapping(info, genericHandler, GenericDynamicHandler#handle)
 ├─ unregisterMapping(removed)
 └─ swap RouteTable (AtomicReference<Map<RouteKey, EndpointDefinition>>) → publish applied generation
```
Replacement = same `RequestMappingInfo` → **no re-registration**; only the RouteTable entry
changes (the generic handler looks the definition up per request). So most publishes are
a pure atomic map swap — registration churn only on path/method changes.

Alternative considered: one catch-all mapping `/dynamic-ai/api/**` with our own
`PathPatternParser` routing. Simpler & fully atomic, but loses Spring MVC per-route
features (produces/consumes negotiation, per-route CORS, actuator mappings listing).
**Decision:** use per-route registration (brief's requirement) but keep the route table
lookup as the source of truth; fallback mode `routing-mode=catch-all` is a config option.

## 4. Request pipeline (GenericDynamicHandler)
| # | Stage | Rejects with |
|---|-------|--------------|
| 1 | Resolve route by `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` → definition | 404 |
| 2 | Kill-switch / suspension check (endpoint, workspace, global) | 503 `endpoint-disabled` |
| 3 | Build `InvocationContext` (principal, mapped roles, workspace, traceId, budget) → `ScopedValue` | — |
| 4 | AuthZ `perm:endpoint:invoke` on resource + ABAC conditions | 403 |
| 5 | Parse & validate params/body vs JSON schema; size limits | 400 (problem + errors[]) |
| 6 | Rate limit (principal × endpoint, workspace) & budget | 429 + `Retry-After` |
| 7 | Cache lookup (if enabled; key includes principal policy fingerprint) | — |
| 8 | Execute backing with timeout (`Backing` → executor port) | 504 / 502 / 500 |
| 9 | Response shaping: projection, masking (`@AiEntityProperty(sensitive=true)`, `@JsonIgnore`, classification > clearance), envelope | — |
| 10 | Audit + metrics (always, incl. failures) | — |

Errors: RFC 9457 `application/problem+json`, `type` URIs under
`https://dynamic-ai/problems/<code>`; never leak stack traces, SQL, or prompt text.

## 5. Executors (ports in core)
- `QueryExecutor` → LLD-05. `AgentInvoker` → LLD-06 (sync JSON; SSE via separate chat endpoint).
- `OperationInvoker` → invokes host bean method via proxy with converted args (Jackson 3
  conversion to parameter types), result → JSON. Only `mutating=false` ops execute directly.
- Write endpoints (F-27): `mutating=true` backings never execute in the request; they return
  `202 Accepted` with a `ChangeProposal` (LLD-11) and `Location: {base}/api/proposals/{id}`.

## 6. OpenAPI (F-23)
`/dynamic-ai/api/openapi.json` generated per snapshot (cached by generation), filtered
to endpoints the caller may invoke. Optional springdoc `GroupedOpenApi` bridge if present.

## 7. Failure modes
| Failure | Behavior |
|---------|----------|
| registerMapping throws for one route | That route skipped + `RegistrationFailure` recorded; others applied; generation marked `PARTIAL` in health |
| Node fails to apply g+1 | Keeps serving g; health reports lag; admin UI shows per-node applied generation |
| Backing timeout | Cancel (virtual thread interrupt + JDBC query timeout) → 504 |
| Referenced query/agent suspended by drift | 503 `resource-suspended` |

## 8. Configuration
| Property | Default |
|----------|---------|
| `dynamic.ai.agent.endpoints.routing-mode` | `per-route` (`catch-all`) |
| `dynamic.ai.agent.endpoints.default-timeout` | `10s` |
| `dynamic.ai.agent.endpoints.max-request-bytes` | `256KB` |
| `dynamic.ai.agent.endpoints.max-page-size` | `200` |
| `dynamic.ai.agent.endpoints.max-routes` | `500` (hard quota; publish rejected above it; live routes never evicted — LLD-12 §4) |
| `dynamic.ai.agent.endpoints.rate-limit.default` | `60/min per principal` |
| `dynamic.ai.agent.endpoints.rate-limit.backend` | `in-memory` (`redis`, `jdbc`) |

Rate limiting in a cluster needs a shared backend; in-memory = per-node limit (documented).
Bucket4j with JCache/Redis/JDBC is the candidate (OQ-07).

## 9. Observability
Timer `dynamic.ai.agent.endpoint.requests{workspace,endpoint,method,status,outcome}`;
span `dai.endpoint {endpoint.id, revision}`; audit event `ENDPOINT_INVOKED`.
Cardinality: tag by endpoint **id**, not raw path.

## 10. Test strategy
MockMvc slice with a fake snapshot; collision tests against host controllers;
concurrent publish-while-serving test (no 404 during replace); contract tests from OpenAPI.
