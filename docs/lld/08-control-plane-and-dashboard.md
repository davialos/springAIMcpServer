# LLD-08: Control Plane (Admin API) & Dashboard

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | control-plane-designer |
| Module(s) | `core` (use cases), `webmvc` (controllers), `admin-ui` |
| Related features | F-10…F-14, F-20, F-30, F-40…F-42, F-61…F-67, F-70…F-75 |

## 1. Purpose & responsibilities
Provide the API and UI through which teams manage catalog overlays, endpoints, queries,
agents, tool bindings, policies, grants, approvals, budgets and audit. The UI is a thin
client; the API is the contract (and is used by GitOps tooling).

## 2. Admin API (`{base}/admin/api/v1`)
Conventions: JSON, `ETag`/`If-Match` optimistic concurrency, RFC 9457 errors (401 unauthenticated vs 403 denied; implemented for the controllers above via `AdminExceptionHandler`), cursor pagination,
`Idempotency-Key` on POSTs that create, CSRF protection for cookie sessions.

| Resource | Endpoints | Key permission |
|----------|-----------|----------------|
| Catalog | `GET /catalog` (summary, all environments), `GET /catalog/entities?q=`, `/catalog/operations?q=` (paged, introspection: off in PROD) — implemented; `/catalog/graph`, `PATCH /catalog/overlays/{ref}` planned | `catalog:read`, `catalog:annotate` |
| Workspaces | `GET/POST /workspaces`, `GET/PATCH(If-Match)/DELETE /workspaces/{ws}`, `GET/POST /workspaces/{ws}/members`, `DELETE .../members/{principal}/{role}` — implemented; workspace-scoped roles only, last owner protected | `workspace:admin` |
| Resources (endpoints, queries, agents, tool bindings, row policies, overlays) | Implemented under `/workspaces/{ws}/resources`: `GET/POST`, `GET /{id}`, `GET/POST /{id}/revisions`, `GET/PUT(If-Match) /{id}/revisions/{rev}`, `:submit`(If-Match) `:approve` `:reject` `:request-changes` `:publish`, `/{id}:suspend|:resume|:deprecate|:retire`; `POST /cluster/generations/{n}:rollback`. Authoring needs the kind's `*:author`, publishing its `*:publish`, reviewing `review:approve`; authoring is off in PROD (`capability-disabled` 403) | `endpoint:author` … |
| Queries | `/workspaces/{ws}/queries...`, `POST .../{id}/preview` | `query:author`, `query:preview` |
| Agents | `/workspaces/{ws}/agents...`, `POST .../{id}/playground` (SSE) | `agent:author`, `agent:playground` |
| Tool bindings | `/workspaces/{ws}/tools...` | `tool:author` |
| Row policies | `/workspaces/{ws}/row-policies...` | `policy:author` |
| Lifecycle | `POST .../revisions/{rev}:submit`, `:approve`, `:reject`, `:publish`, `:rollback`, `:deprecate` | `*:submit`, `review:approve`, `*:publish` |
| Grants | `GET/POST /workspaces/{ws}/grants`, `DELETE .../grants/{id}` — implemented; grantable (invocation) permissions only, conditions validated | `grant:manage` |
| Role mappings | `GET/POST /role-mappings`, `GET/PUT(If-Match)/DELETE /role-mappings/{id}`, `:enable`, `:disable` — implemented; mapping to a platform-wide role needs `PLATFORM_ADMIN` | `rolemapping:manage` |
| Service accounts | `GET/POST /workspaces/{ws}/service-accounts`, `:enable`, `:disable`, `GET .../{id}/keys`, `DELETE .../{id}/keys/{key}` — implemented; key issuance pending (OQ-37) | `serviceaccount:manage` |
| Budgets | `GET/POST /workspaces/{ws}/budgets` and `/budgets` (global), `GET/DELETE .../{id}`, `PUT .../{id}/limits` (If-Match), `:enable`, `:disable` — implemented; detail shows current-period usage | `budget:manage` |
| Usage | `GET /workspaces/{ws}/usage/summary`, `/usage/series` (and `/usage/...` global), `GET/POST /prices` — implemented; series limited to 32 days, HOUR or DAY buckets | `budget:manage` or `audit:read` |
| Traces | `GET /workspaces/{ws}/traces/turns`, `/turns/{id}`, `/model-calls`, `/tool-invocations?violationsOnly=` — implemented; redacted args, counts and hashes only | `audit:read` |
| Conversations | `GET /dynamic-ai/api/conversations`, `GET .../{id}/messages`, `POST .../{id}:close`, `DELETE .../{id}` (erase) — implemented, owner only, USER/ASSISTANT messages only | authenticated owner |
| Kill switches | `GET /kill-switches[?workspaceId]`, `GET /kill-switches/history?since=`, `POST /kill-switches`, `DELETE /kill-switches/{id}` (implemented) | `ops:killswitch` |
| Audit | Implemented: `GET /audit/workspaces/{ws}/events`, `/audit/actors/{id}/events`, `/audit/denials`, `/audit/proposals/{id}/events`, `/audit/turns/{id}/events`, `/audit/chains/{chain}/verify` (window `from`/`to`, `limit` ≤ 200, `offset`); `GET /audit/export` planned | `audit:read` |
| Cluster | `GET /cluster/nodes?aliveWithinSeconds=` (applied generation per node, `converged`) — implemented | `ops:killswitch` until `ops:read` exists (OQ-34) |
| Bootstrap | `GET /me` — caller, roles, environment tier and capability flags — implemented | authenticated |
| Bundles | `POST /bundles:export`, `POST /bundles:import` (dry-run default) | `platform:admin` |

## 3. Dashboard screens
| Screen | Persona | Main actions |
|--------|---------|--------------|
| Home | all | My workspaces, pending reviews, alerts (drift, budget, failures) |
| Catalog explorer | P3/P4 | Search, entity graph (ER), operation detail with annotation descriptions and per-layer provenance (code / policy file / overlay / kill switch), overlay edit, classification |
| Endpoint builder | P4 | Path/method, params (schema form), backing picker, response shaping, test call |
| Query builder | P4 | Entity → attribute tree (from catalog), filter tree editor, params, preview grid, explain (if allowed) |
| Agent studio | P4 | Prompt editor with variables, model select, tool picker (catalog + queries + MCP), memory/guardrails/limits, eval cases |
| Playground | P4 | Chat with draft revision, tool-call timeline, tokens/cost, redaction preview |
| Review inbox | P5 | Diff between revisions (semantic diff: tools added, classification raised), eval results, approve/reject with comment |
| Access | P2/P3 | Members, role mappings (IdP group → role), grants matrix, service accounts & keys, access review export |
| Operations | P2 | Usage & cost charts, budgets, kill switches, cluster generation status, drift report |
| Audit | P7 | Filterable log, event detail, export |

UI tech: SPA (framework decided in OQ-08), built at library build time, served from the
JAR under `{base}/admin/`; strict CSP, no external CDN, i18n-ready, WCAG 2.2 AA.
Auth: rides the host's session/OIDC login (redirect to host login); for bearer-only hosts,
the UI uses the host's OAuth2 client via BFF pattern (documented integration recipe).

## 4. Semantic diff for reviews
Reviewer sees a risk-oriented diff, not only JSON: new tools (+ mutating flag), newly
referenced entities & classifications, changed row policies, model/provider change,
limit increases, prompt diff. Risk score drives whether approval is mandatory (policy table in security/01 §6).

## 5. Failure modes
| Failure | Behavior |
|---------|----------|
| Concurrent edits | 412 Precondition Failed with current ETag |
| Publish fails validation (drift, collision, dangling dependency) | 409 `conflict` with the findings in `detail`; revision stays APPROVED (implemented; a dedicated 422 type is still open) |
| Config DB down | Admin API 503; data plane unaffected (last-good snapshot) |

## 6. Test strategy
API contract tests (OpenAPI for admin API published); Playwright E2E for main journeys;
axe accessibility checks; authz matrix tests (every endpoint × every role).
