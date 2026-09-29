# UI Feature / API Gap Analysis

Status: Draft v1 · Date: 2026-09-29 · Scope: what a UI (admin dashboard, playground, review UI, embeddable components) can do with the backend as implemented today, what APIs are missing, and what validation the UI data and user flows need.

Sources: LLD-08, LLD-11, LLD-13, feature catalog, and the code in `webmvc`, `autoconfigure`, `persistence`, `security`, `ai`, `query`. Items marked **(verify)** were not confirmed in code.

## 1. Executive summary

- Only **3 REST controllers' worth of API** exist: catalog read (3 GETs), resource lifecycle (8 endpoints), agent chat (sync, SSE stream, SSE replay), plus the dynamic data plane under `/dynamic-ai/api/**`.
- The **persistence layer is far ahead of the HTTP layer**: stores for workspaces, members, service accounts and keys, role mappings, grants, budgets, kill switches, proposals, conversations, turns, audit and MCP clients all exist with no REST controller. Most UI screens need thin controllers, not new domain logic.
- **No UI module exists** (`admin-ui`, `review-ui` are absent; OQ-08 framework choice is still open).
- The existing controllers **do not meet the LLD-08 conventions** the UI depends on: no RFC 9457 bodies on admin errors, no ETag/If-Match, no pagination, no bean validation.
- Several **pre-existing defects** would surface as soon as a UI calls these endpoints (section 4).

## 1a. Implementation status (2026-09-29)

First cross-cutting slice implemented in `autoconfigure` (same place as the existing admin controllers): `GET /me`, audit log viewer (`/audit/**`), kill switches (`/kill-switches`), `GET /cluster/nodes`, shared 401/403/RFC 9457 handling (`AdminApi`, `AdminExceptionHandler`, new `ProblemCode`s `UNAUTHENTICATED`, `CONFLICT`, `PRECONDITION_FAILED`), and `AuditTrail` / `KillSwitchStore` beans. Second slice: proposal review API (`/dynamic-ai/api/proposals`: list, get, confirm, approve, reject, decline with If-Match/ETag), workspaces and members, role mappings (with a platform-role escalation guard), grants, service accounts and key revoke, plus stores and a `dynamic.ai.agent.write.retention` property. Third slice: budgets (workspace and global routes, If-Match), usage summary/series and model prices, trace viewer (turns, model calls, tool invocations), and owner-scoped conversation history with close and erase; `UsageLedger.hourlySeries` and `TelemetryStore.findConversationById` added in persistence. Fourth slice: budget enforcement in the invocation path (LLD-10 §6; accuracy limits in OQ-40). Turn telemetry is now written by the invoker (trace viewer has data; see OQ-43 for gaps such as tool invocations and conversation persistence). Not yet done: auditor conversation access (OQ-38), proposal edit and apply (OQ-36), API key issuance (OQ-37), the section 4 defects in the existing resource and chat controllers, ETag/If-Match, proposals, access management, budgets, usage, trace viewer, conversation history, application log tail (OQ-33).

## 2. Existing API inventory

| Area | Endpoint | Notes |
|------|----------|-------|
| Catalog | `GET /dynamic-ai/admin/api/v1/catalog`, `/entities`, `/operations` | Read-only summaries |
| Resources | `GET/POST /admin/api/v1/workspaces/{ws}/resources` | List (optional `kind`), create with first draft |
| | `GET .../resources/{id}`, `GET .../{id}/revisions` | Detail with live revision; revision list |
| | `POST .../{id}/revisions` | New draft |
| | `POST .../{id}/revisions/{rev}:publish`, `POST .../{id}:suspend`, `:resume` | Lifecycle |
| Agent chat | `POST /dynamic-ai/api/agents/{slug}/chat`, `/chat/stream`, `GET` turn replay | SSE ids are `turnId:seq`; 256-event / 5 min buffer |
| Dynamic endpoints | `/dynamic-ai/api/**` via `GenericDynamicHandler` | Runtime-registered |
| MCP | `/dynamic-ai/mcp` | Tool provider, not a UI API |

## 3. Screen-by-screen feasibility (LLD-08 section 3)

Legend: **Ready** = API exists and is enough. **Controller only** = store/port exists, needs REST + validation. **New backend** = needs domain work too.

| Screen / feature | Status | What exists | What is missing |
|------------------|--------|-------------|-----------------|
| Bootstrap (who am I, roles, env tier, feature flags) | New backend | `DaiPrincipal`, `EnvironmentTier`, `Capability` | `GET /admin/api/v1/me` returning principal, effective permissions, workspaces, tier, locked capabilities. The UI needs this before rendering anything |
| Home (workspaces, pending reviews, alerts) | Controller only | `WorkspaceStore.listActive`, `ChangeProposalStore.approvalInbox`, `ConfigStore` review queries | Aggregating endpoint; drift and budget alerts source |
| Catalog explorer (F-10) | Partial | 3 GETs | Search/filter, entity detail with attributes, provenance per layer (`PolicyProvenance` exists in core), scan fingerprint |
| Entity graph (F-11) | New backend | `RelationDescriptor`, `RelationPath` | `GET /catalog/graph` |
| Overlay edit, classification (F-12, F-13) | New backend | Policy merge in core | No overlay store or `PATCH /catalog/overlays/{ref}` |
| Endpoint / query / agent authoring (F-20, F-30, F-40) | Partial | Generic resource create/draft/publish | Edit draft in place (store has `editDraft` with `expectedRowVersion`), submit, references/dependencies, per-kind spec schema and validation |
| Review inbox (F-64) | Controller only | Store has submit, review (with `SegregationOfDutiesException`), approve, reject, expire | Endpoints for `:submit`, `:approve`, `:reject`, `:deprecate`, `:rollback`, semantic diff (LLD-08 section 4) |
| Query preview / explain (F-32) | New backend | `QueryExecutor`, `QueryValidator` | Preview endpoint capped at 20 rows, running as the author |
| Playground (F-42) | New backend | `AgentInvoker`, SSE contract | Invoke a **draft** revision; tool-call timeline events; cost per turn |
| Chat / agent UI (F-43) | Ready with fixes | sync, SSE, replay | See section 4 defects |
| Write-proposal review UI (F-45, F-52) | Controller only | `ChangeProposalStore`: `pendingOf`, `edit`, `confirm`, `approve`, `reject`, `decline`, states | Entire `/api/proposals` review API (LLD-11 section 8), review payload builder, SSE `proposal.*` events **(verify)** |
| Display components (F-51) | New backend | none | Component JSON schema registry, `render_component` validation, provenance check |
| Access: members, role mappings | Controller only | `WorkspaceStore.addMember/removeMember/members`, `RoleMappingStore` CRUD | Controllers |
| Access: grants matrix | Controller only | `GrantStore.create/revoke/grantsInWorkspace` | Controller |
| Access: service accounts and keys | Controller only | `ApiKeyStore` | Controller; one-time secret reveal flow |
| Access: MCP clients and consent | Controller only | `McpClientStore` | Controller |
| Budgets (F-70) | Controller only | `BudgetStore` | Controller |
| Kill switches (F-73) | Controller only | `KillSwitchStore.set/clear/listActive/history` | Controller |
| Cluster status | Controller only | `ConfigStore` cluster status + heartbeats | `GET /cluster/nodes` |
| Usage and cost dashboard (F-71) | New backend | `UsageLedger`, `UsageHourly` entities | Read queries and endpoint |
| Trace viewer (F-72) | Controller only | `TelemetryStore.turnsOfWorkspace`, `findTurn`, `modelCallsOfTurn` | Controller with redaction |
| Conversation history / erase (F-44) | Controller only | `conversationsOf`, `messages`, `eraseConversation` | Controller |
| Audit viewer and export (F-66) | Controller only | `AuditTrail.eventsOf*`, `denials`, `verify` | Controller; export (JSON lines/CSV) |
| Bundles, promotion (F-74, F-75) | New backend | none | Deferred (v1.x) |
| OpenAPI for dynamic endpoints (F-23) | New backend | none | `/dynamic-ai/api/openapi.json` |

## 4. Defects in existing APIs that block or mislead a UI

Verified in code:

1. **Admin errors carry no body.** `ResourceAdminController` returns `ResponseEntity.badRequest().build()` / `status(CONFLICT).build()`. The UI cannot show which field failed. LLD-08 and F-21 require RFC 9457 problems with field errors. `ProblemDetailFactory.buildValidation` and `FieldViolation` exist and are only used by chat.
2. **Null kind causes a 500.** `createResource` calls `request.kind().toUpperCase()` before any null check; a body without `kind` throws `NullPointerException`. `toUpperCase()` is also locale-sensitive; use `Locale.ROOT`.
3. **Unauthenticated returns 403, not 401.** Chat pre-checks return 403 "Authentication required". A UI cannot distinguish "log in again" from "you lack permission".
4. **Turn replay has no owner check.** `replayTurnStream` authorizes `ENDPOINT_INVOKE` on the agent, then serves any `turnId` from the buffer. Another authorized user who learns a turn id could replay someone else's stream. Bind turns to the principal in `TurnEventBuffer` and return 404 on mismatch.
5. **`clientRequestId` is not an idempotency key.** It is passed through, but a random UUID is substituted when absent and nothing dedupes retries. UI double-submit will create two turns.
6. **No message length cap** on `ChatRequest.message`; only blank is rejected. `REQUEST_TOO_LARGE` exists but is unused here.
7. **No optimistic concurrency over HTTP.** `ConfigStore.editDraft` takes `expectedRowVersion`, but there is no edit endpoint and no `ETag`/`If-Match`. Two editors would silently overwrite once an edit endpoint is added naively.
8. **Unpaginated lists** (`listResources`, `listRevisions`, catalog lists) against the cursor pagination convention.
9. **`specJson` is a raw string** checked only for blank; malformed JSON or a wrong-shaped spec is accepted and fails later at publish.
10. **`ProblemCode` lacks codes the flows need**: unauthenticated (401), precondition-failed/stale version (412), conflict (409), validation-failed (422), proposal-expired (410), idempotency-replay.
11. **First-token timeout is a hardcoded 20 s** in `chatStream`; UIs cannot tune or read it.

**Status (fifth slice):** items 1 to 9 and 11 are fixed: resource and catalog endpoints answer RFC 9457 problems, validate input (kind, slug, spec object, size, credentials), page their lists, return `ETag` and require `If-Match`, gained the full lifecycle (edit, submit, review, deprecate, retire, rollback) and honour the environment capability matrix; chat answers 401 for unauthenticated callers, caps the message length, deduplicates `clientRequestId` per node, binds replay to the turn owner, and takes its idle timeout from `dynamic.ai.agent.chat.*`. Also fixed while there: a streamed chat subscribed to the agent stream twice (two turns per request), and timeout and error events were not buffered for replay. Item 10 was done earlier. The chat turn id is now one id end to end: the controller generates it (UUIDv7), passes it to the invoker via `AgentChatRequest.turnId`, and it appears in the SSE ids, the replay URL, `turn.start`, the sync response and error events.

To verify: whether `DaiAuthorizationManager`/the filter chain enforces `Permission` per admin path, since neither admin controller calls `AuthorizationEngine`; CSRF handling for cookie sessions on POSTs.

## 5. APIs to add (by priority)

**P0 — UI cannot start without these**
- `GET /admin/api/v1/me`
- Error contract: RFC 9457 on all admin errors, `ProblemCode` additions, 401 vs 403
- Draft editing: `PUT .../revisions/{rev}` with `If-Match`; return `ETag` on every revision read
- Review lifecycle: `POST .../revisions/{rev}:submit|:approve|:reject|:deprecate`, `POST .../resources/{id}:retire`, `POST /generations/{n}:rollback`
- Pagination on all list endpoints

**P1 — core journeys**
- Proposals: `GET /api/proposals`, `GET/PATCH /api/proposals/{id}`, `POST :confirm|:approve|:reject`
- Workspaces and members; grants; service accounts and keys; role mappings; kill switches; budgets
- Audit: `GET /audit`, `GET /audit/export`, chain verify
- Cluster: `GET /cluster/nodes`
- Query preview; agent playground on draft revisions

**P2 — completeness**
- Catalog graph and overlays; usage dashboard; trace viewer; conversation history and erase; MCP client registry; semantic diff; drift report; OpenAPI generation; component schema registry

## 6. Validation model

Four layers; the server is authoritative and the client mirrors it for UX only.

| Layer | Purpose | Failure shape |
|-------|---------|---------------|
| L1 Transport | JSON parse, content type, size caps | 400 / 413 / 415 problem |
| L2 Field | Bean Validation on request records, per-kind spec schema | 422 problem with `errors[{field, code, message}]` |
| L3 Domain/state | State machine, segregation of duties, references, version | 409 / 412 / 410 problem with stable code |
| L4 Authorization | Permission, workspace scope, row visibility | 401 / 403 (404 to hide existence across workspaces) |

### 6.1 Field rules (proposed; add as Bean Validation on request records)

| Field | Rule |
|-------|------|
| `slug` (resource, workspace, agent) | `^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$`, unique per workspace+kind (map unique violation to 409 `slug-taken`) |
| `kind` | Required; one of `ResourceKind`; parse with `Locale.ROOT` |
| `name` / `description` | name 1–120, description ≤ 2000, no control characters |
| `specJson` | Required, valid JSON object, ≤ 256 KB, validates against the per-kind JSON Schema, `schemaVersion` supported |
| `changeSummary` | ≤ 500 |
| `reason` (suspend, rollback, kill switch) | Required, 1–500 |
| `message` (chat) | 1–`maxMessageChars` (configurable, default 8000) |
| `conversationId` | Must exist, belong to the caller and agent |
| `clientRequestId` | ≤ 64, `[A-Za-z0-9_-]`; real dedupe window |
| Grant `permission` | Must be a `Permission` of kind GRANT; target must exist in the workspace |
| Role mapping rule | Must parse; role must be a `FrameworkRole`; workspace required for workspace roles |
| Budget limits | Positive; money as `long` micros with ISO currency; soft ≤ hard |
| API key expiry | Future; ≤ configured max lifetime; CIDR list parses via `CidrBlock` |
| Kill switch `expiresAt` | Future if present |
| Proposal `contentHash` | `sha256:` + 64 hex; must equal current hash |
| Proposal edits | Only fields flagged writable and non-sensitive; values validated against the field schema in the payload |
| Query definition | Run `QueryValidator` on save, not only at publish; parameter names unique; page size ≤ configured cap |
| Cursor | Signed; forged cursors rejected (F-17) |

### 6.2 User-flow validation

| Flow | Server must enforce | UI must do |
|------|---------------------|------------|
| Create resource | Slug unique, spec valid, caller has `*:author` in workspace | Inline slug check, schema-driven form, disable submit while pending |
| Edit draft | `If-Match` row version, state is DRAFT | Show "changed by someone else" on 412 with reload/merge option |
| Submit for review | Draft valid, dependencies/references exist, risk score computed | Show findings list before submit |
| Approve / reject | Approver ≠ author (`SegregationOfDutiesException` → 403 `segregation-of-duties`), required approvals count, reject needs comment | Hide approve for own drafts; require comment on reject |
| Publish | State is APPROVED, live set closed (no dangling dependencies), publish lock | Show 422 findings; keep revision APPROVED |
| Suspend / resume | Reason required, resource live/suspended respectively | Confirm dialog with reason field |
| Rollback | Target generation exists, reason required, all pinned revisions rollbackable | Preview affected resources, typed confirmation |
| Chat | Agent published, not killed, authenticated, authorized, rate limit, budget, message valid | Disable send while streaming; keep `clientRequestId` per send; resume with `Last-Event-ID` |
| Stream reconnect | Turn owner matches, within replay window, else 404 | Fall back to fetching conversation history if replay is gone |
| Proposal confirm | Same user, hash matches, not expired, version unchanged, authz re-run, idempotency key | Confirm shows exactly the hashed content; one click = one `Idempotency-Key` |
| Proposal edit | Writable fields only, re-validate, new hash | Re-render diff from server response, never from local state |
| Grant create | Permission is GRANT kind, principal in workspace, no duplicate | Show effective access preview |
| Service account key | Secret shown once, hashed at rest, prefix lookup | One-time reveal with copy, warn before close |
| Kill switch | `ops:killswitch`, reason, optional expiry | Show blast radius (agent/endpoint/workspace/global) before confirming |
| Production tier | Authoring and introspection off (LLD-12); UNKNOWN tier = PROD | Read `/me` capabilities and hide locked screens |

### 6.3 Cross-cutting rules for all UI endpoints

- `Idempotency-Key` on creating POSTs and proposal confirm; same key + same body returns the original result, different body returns 409.
- `ETag`/`If-Match` on every mutable resource; 428 when missing on mutation, 412 when stale.
- CSRF protection for cookie sessions; bearer-only hosts use the BFF pattern.
- Errors never echo secrets, prompt text, or row data; messages are stable codes plus sanitized detail.
- Every mutating admin call appends an audit event with actor, target, and outcome.
- List endpoints: cursor pagination, max page size, deterministic sort.

## 7. Recommended sequencing

1. Fix defects 1–6 in section 4 and add the error contract (P0). Small, unblocks every screen.
2. Add `/me`, draft edit with ETag, and lifecycle endpoints.
3. Add controllers over existing stores in the order: proposals, access (members, grants, keys, mappings), kill switches and budgets, audit, cluster.
4. New-backend items (query preview, playground, overlays, usage, graph).
5. Choose the UI framework (OQ-08) after step 2 so the contract tests exist first.

## 8. Open questions to record in `docs/open-questions.md`

- Should admin errors move to `@ControllerAdvice` with `ProblemDetail`, replacing per-controller `.build()` calls?
- Maximum spec size and per-kind spec schemas: who owns them (core vs persistence)?
- Replay buffer scope: per node in memory breaks the stateless default (ADR-0021) behind a round-robin balancer; move to PostgreSQL or require sticky routing for stream resume?
- Where do catalog overlays persist (no store exists today)?
