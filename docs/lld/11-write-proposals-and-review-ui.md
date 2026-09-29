# LLD-11: Write Proposals, Review UI Components & Host Versioning/Audit Reuse

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | agent-runtime-designer (with dynamic-runtime-designer, access-management-architect, control-plane-designer) |
| Module(s) | `core` (proposal domain, ports), `jpa` (write executor, versioning adapters), `ai` (proposal tools), `webmvc` (review API), `review-ui` (embeddable UI components) |
| Related features | F-27, F-28, F-45, F-51, F-52, F-53 |
| Related ADRs | ADR-0008, ADR-0009 |
| Decision source | OQ-12 resolved by product owner 2026-09-27 |

> **Implemented (2026-09-29): creation.** A tool bound in PROPOSE mode creates the proposal (`StoreProposalService`
> over `ChangeProposalStore`): target kind `HOST_OPERATION` (the tool's operation), the arguments after the binding's
> constraints as the record's after-values (`dai_change_proposal_record.after_values`) and `target_args`, owner = the
> caller, tied to the tool call, turn and channel (`AGENT_TOOL` or `MCP_TOOL`), `contentHash` over target, kind and
> canonical arguments, TTL `write.proposal-ttl`. Idempotent per turn or MCP request. Gated by
> `dynamic.ai.agent.write.enabled` (default **off**: PROPOSE tools answer `writes_disabled`); a delete, or every
> proposal with `write.require-approver`, needs a second person. Refused with a stable code: an operation that is
> unknown, read-only or not linked to a record type, arguments that are not a JSON object.
>
> **Implemented (2026-09-29): the write executor** (`ProposalApplier`, HOST_OPERATION only). It runs the host operation
> through its Spring proxy **on the request thread of the owner, in their `SecurityContext`** (host method security,
> transactions, validation, `@Version`, auditing and Envers see the real user, ADR-0008) and is reachable only from the
> review API, never from a tool call. `POST /proposals/{id}:confirm` applies right away when no approver is needed;
> a proposal that waited for approvers is applied by the owner with `POST /proposals/{id}:apply`. Checks, in order:
> owner, state `CONFIRMED`, `write.enabled`, capability `REVIEWED_WRITES`, `data:write-confirm` still held, not expired
> (`FAILED/expired`, 410), operation still present, enabled and not read-only, stored content still hashes to what was
> confirmed (`FAILED/content_mismatch`), a per-node bulkhead (`write.max-concurrent-applies`, 429). Then
> `CONFIRMED → APPLYING` is a compare-and-set (a double click or second node cannot run it twice) and the outcome is
> `APPLIED`, `CONFLICT` (host optimistic-lock failure, `version_conflict`) or `FAILED` (`execution_error`,
> `access_denied`); the host's exception message is never stored or returned. Audited as `PROPOSAL_APPLIED`,
> `PROPOSAL_CONFLICT`, `PROPOSAL_FAILED`. A crash between the host commit and `APPLIED` leaves `APPLYING`; the
> maintenance runner marks it `FAILED/APPLY_TIMEOUT` for an operator to verify (never retried, §10).
>
> **Not implemented yet:** the before-snapshot and base version (`VersioningAdapter`; so no conflict is detected before
> the host runs, only the host's own optimistic lock), the host revision reference on `APPLIED`, edit (`PATCH`),
> `ENTITY_WRITE`, bulk, step-up authentication, and the `ui.component` events.

## 1. Purpose & responsibilities
Let agents (and write endpoints) **propose** changes to host data, render those proposals
to the user through UI components for review/edit, and apply them **only after explicit
user confirmation** — writing to the host's **existing tables** through the host's
**existing write paths**, so the host's existing **versioning tables**, **audit tables**
and **identity flows** record the change exactly as if the user had made it in the host UI.

This component does NOT:
- invent its own data-history mechanism (the host's versioning is the single owner of data history);
- write with SQL that bypasses the host's JPA listeners, Envers, triggers or service rules;
- let a model execute a write directly — the model can only *propose*.

## 2. Core idea — "propose → review → confirm → apply"
```
 LLM ──(mutating tool call)──► ProposalTool ──► ChangeProposal (PROPOSED, nothing written)
                                                   │ stores: target, before-snapshot + version, after-values, diff, validation
                                                   ▼
 UI  ◄──(SSE `ui.component` / review API)──── ReviewComponentPayload (record-diff / record-form / bulk-table)
  │ user inspects, optionally edits allowed fields, clicks Confirm (or Reject)
  ▼
 Review API ──► re-authorize as the SAME user ─► re-validate ─► optimistic version check
                                                   │
                                                   ▼
             WriteExecutor (in host transaction, as the user)
               ├─ HOST_OPERATION: call host service method via Spring proxy (preferred)
               └─ ENTITY_WRITE:   EntityManager persist/merge/remove on allow-listed entity
                       │
                       ├─► host @Version check              (optimistic locking)
                       ├─► host Spring Data auditing        (@CreatedBy/@LastModifiedBy via AuditorAware ← SecurityContext = real user)
                       ├─► host Envers / history tables     (version rows written by host mechanism)
                       ├─► host DB triggers / temporal tables
                       └─► host domain events / outbox
                       ▼
             Proposal APPLIED + link to host revision id ──► dai_audit (decision trail) ──► result back to agent/UI
```

## 3. Domain model
```java
// design sketch
public record ChangeProposal(ProposalId id, ConversationRef conversation /* nullable for endpoint-originated */,
        WorkspaceId workspace, String proposedBySubject,          // principal who owns the conversation
        ProposalOrigin origin,                                    // AGENT_TOOL | WRITE_ENDPOINT
        WriteTarget target,                                       // sealed, see below
        ChangeKind kind,                                          // CREATE | UPDATE | DELETE | BULK
        List<RecordChange> changes,                               // 1..maxRecords (default 1; bulk ≤ 100)
        ValidationReport validation,                              // bean validation + host validators + policy checks
        ProposalState state, Instant createdAt, Instant expiresAt,
        ApprovalRequirement approval,                             // SELF_CONFIRM | SELF_CONFIRM_PLUS_APPROVER(n)
        String contentHash) {}                                    // hash over target+changes; confirm must match

sealed interface WriteTarget permits HostOperationTarget, EntityWriteTarget {}
record HostOperationTarget(CatalogElementRef operation, Map<String, Object> args) implements WriteTarget {}
record EntityWriteTarget(CatalogElementRef entity) implements WriteTarget {}

public record RecordChange(Object entityId /* null for CREATE */,
        Map<String, Object> before,                               // masked view of current row (only exposed attributes)
        Map<String, Object> after,                                // proposed values
        List<FieldDiff> diff,                                     // attribute, oldValue, newValue, editable
        VersionToken baseVersion) {}                              // @Version value / row version / Envers revision / etag

public record VersionToken(VersionKind kind, String value) {}     // JPA_VERSION | ENVERS_REVISION | ROW_HASH | CUSTOM
enum ProposalState { PROPOSED, EDITED, AWAITING_APPROVAL, CONFIRMED, APPLYING, APPLIED, REJECTED, EXPIRED, CONFLICT, FAILED }
```

### State machine
```
PROPOSED ──edit──► EDITED ──(re-validate)──┐
   │                                        │
   ├──reject──► REJECTED                    │
   ├──ttl────► EXPIRED                      ▼
   └──confirm (same user, hash match) ─► [approval needed?] ─yes─► AWAITING_APPROVAL ─approve─┐
                                               │ no                                       reject──► REJECTED
                                               ▼                                              │
                                           CONFIRMED ◄───────────────────────────────────────┘
                                               ▼
                                           APPLYING ──ok──► APPLIED (hostRevisionRef)
                                               │
                                               ├─ version mismatch ─► CONFLICT (offer re-propose with fresh before-snapshot)
                                               └─ error/validation ─► FAILED (reason; nothing committed)
```
Transitions are compare-and-set on `dai_change_proposal.state` (idempotent confirm via `Idempotency-Key`).

## 4. Write modes

| Mode | When | Pros | Rules |
|------|------|------|-------|
| **HOST_OPERATION** (default, preferred) | Host exposes a write service method `@AiExposedAction(readOnly=false)` | All business rules, validation, events, versioning, auditing run exactly as in host UI | Method invoked via proxy; args = proposal after-values; host `@PreAuthorize` applies |
| **ENTITY_WRITE** | Entity attributes marked `@AiEntityProperty(writable=true)` and no suitable service method | Works for simple CRUD tables | Only through `EntityManager` (JPA lifecycle ⇒ listeners, `AuditingEntityListener`, Envers, `@Version`, Bean Validation fire). Only attributes flagged writable. Never `CriteriaUpdate/Delete` or native SQL (would bypass Envers/listeners). Requires approval policy by default |

Bulk (`BULK`) = N record changes applied in **one transaction, all-or-nothing**, capped (default 100).

## 5. Reusing the host's versioning (VersioningAdapter SPI)
The host already versions its tables; we **read** that mechanism to build before-snapshots,
history views and undo — we never write version rows ourselves.
```java
// design sketch
public interface VersioningAdapter {
    boolean supports(CatalogElementRef entity);
    VersionToken currentVersion(CatalogElementRef entity, Object id);
    List<VersionEntry> history(CatalogElementRef entity, Object id, int limit);        // for "history" tab in review UI
    Optional<HostRevisionRef> revisionCreatedBy(TransactionOutcome outcome);           // link proposal → host revision after apply
}
```
| Adapter (auto-detected) | Detection | currentVersion | history | revision link |
|-------------------------|-----------|----------------|---------|---------------|
| `JpaVersionAdapter` | `@Version` attribute in metamodel | version attribute | — | version value after flush |
| `EnversAdapter` | `org.hibernate.envers` on classpath + entity `@Audited` | latest revision number | `AuditReader.createQuery().forRevisionsOfEntity` | revision number of the tx (`AuditReader.getCurrentRevision`) |
| `SpringDataEnversAdapter` | `RevisionRepository` bean for the entity | `findLastChangeRevision` | `findRevisions` | same as Envers |
| `HistoryTableAdapter` (config-driven) | `dynamic.ai.agent.write.versioning.tables.<entity>` mapping (history table, key, version col, valid_from/to) | max version col | select from history table (read-only JDBC) | version col after commit |
| `TemporalTableAdapter` | SQL Server system-versioned / MariaDB system-versioned tables (config) | row period start | `FOR SYSTEM_TIME` query | period start |
| `RowHashAdapter` (fallback) | none of the above | hash of exposed attributes | — | — |

Conflict detection: before apply, `currentVersion == baseVersion` else `CONFLICT`. For JPA
`@Version` the host's own optimistic lock is also the final guard (`OptimisticLockException` → `CONFLICT`).
**Undo** = new proposal whose after-values = a selected historical version (still reviewed & confirmed).

## 6. Reusing the host's audit & identity flows
- The write runs on a thread whose `SecurityContext` = the confirming user's `Authentication`
  (ADR-0008). Therefore host `AuditorAware` → `@CreatedBy/@LastModifiedBy`, Envers
  `RevisionListener` (who changed), DB session context (optional `SessionContextPropagator`
  SPI e.g. `SET app.user_id` / Oracle `DBMS_SESSION.SET_IDENTIFIER` for trigger-based audit)
  all record the **real user**, not a service account.
- Host revision entity can be enriched via `RevisionEnricher` SPI (host opt-in) with
  `proposalId`, `agentId`, `channel=AI_ASSISTED` so host history shows the change was AI-assisted.
- Our `dai_audit_event` records the **decision trail** only (proposed by agent X, shown to
  user, edited fields, confirmed at, approved by, host revision ref). Data history remains
  owned by the host tables (one owner per fact).
- Optional **step-up / recent authentication** for confirms: require `auth_time` ≤ N minutes
  (OIDC) or re-auth via the host's flow; configurable per classification.

## 7. UI components (model → UI data contract)
Two families, both driven by **validated structured payloads** — the model never emits HTML/JS.

### 7.1 Display components (read-only, any agent)
Agent returns data to the UI via a `render_component` tool (or `StructuredOutputValidationAdvisor` schema):
`record-card`, `record-table`, `key-value`, `chart` (bar/line/pie, data only), `timeline`, `entity-link`.
```json
{ "component": "record-table", "version": 1,
  "title": "Recent orders for ACME", "entity": "entity:com.acme.order.Order",
  "columns": [{"attr":"id"},{"attr":"status"},{"attr":"total","format":"currency"}],
  "rows": [ { "id": 101, "status": "PAID", "total": "120.50" } ],
  "sourceToolCallId": "call_7" }
```
Server validates payload against the component JSON schema registry, re-masks attributes by
caller clearance, and **rows must come from a tool result of this turn** (`sourceToolCallId`)
— the model cannot fabricate data shown as "from the database" (provenance check by hash).

### 7.2 Review components (write proposals)
`record-diff` (UPDATE), `record-form` (CREATE; prefilled, editable within schema),
`delete-confirm`, `bulk-change-table`. Payload is **generated by the server from the
ChangeProposal**, not by the model:
```json
{ "component": "record-diff", "version": 1, "proposalId": "prp_01J…",
  "entity": "entity:com.acme.order.Order", "entityId": 101,
  "fields": [ {"attr":"status","before":"PAID","after":"SHIPPED","editable":true,"schema":{"enum":["PAID","SHIPPED"]}},
              {"attr":"trackingNo","before":null,"after":"1Z999","editable":true,"schema":{"type":"string","maxLength":40}} ],
  "baseVersion": {"kind":"ENVERS_REVISION","value":"5812"},
  "history": [ {"rev":5812,"at":"2026-09-20T10:01:00Z","by":"jdoe"} ],
  "validation": { "errors": [], "warnings": ["Customer is on credit hold"] },
  "approval": "SELF_CONFIRM", "expiresAt": "2026-09-27T12:15:00Z",
  "contentHash": "sha256:…",
  "actions": ["confirm","edit","reject"] }
```

### 7.3 Delivery
- SSE event types added to the chat stream: `ui.component`, `proposal.created`, `proposal.updated`, `proposal.applied`.
- **Embeddable components** shipped in `review-ui` module as framework-agnostic **Web
  Components** (`<saimcp-record-diff proposal-id>`, `<saimcp-record-table>`, …) plus a thin
  JS client, so host UIs (React/Angular/Thymeleaf) embed them and keep their own styling
  via CSS custom properties. The admin dashboard & playground reuse the same components.
- Hosts may instead render payloads with their own components (payload schemas are the contract, versioned).

## 8. Review API (`{base}/api/proposals`)
| Endpoint | Purpose | AuthZ |
|----------|---------|-------|
| `GET /proposals/{id}` | Review payload (re-masked for caller) — implemented at `/dynamic-ai/api/proposals` (list `?scope=mine\|inbox`, get, `:confirm`, `:approve`, `:reject`, `:decline`; edit pending, OQ-36) | owner or approver |
| `PATCH /proposals/{id}` (`If-Match`) | Edit editable fields → re-validate → new contentHash | owner |
| `POST /proposals/{id}:confirm` (`Idempotency-Key`, body: `contentHash`) | Confirm | owner + `data:write-confirm` on target |
| `POST /proposals/{id}:approve` / `:reject` | Second-person approval | `data:write-approve`, ≠ owner |
| `POST /proposals/{id}:apply` | Apply a confirmed proposal (owner; implemented) — needed only after approvers; `:confirm` applies directly otherwise | owner + `data:write-confirm` + capability `REVIEWED_WRITES` |
| `GET /proposals?state=` | My pending proposals | owner |
After apply, the agent conversation receives a tool result `{status: APPLIED, hostRevision}` and continues.

## 9. Security
- Model can only create proposals; confirm requires an **HTTP request from the user's
  authenticated session** (not a tool call) with matching `contentHash` → no prompt-injection path to a write.
- CSRF protection on confirm (cookie sessions); proposal IDs unguessable (ULID + owner check).
- Confirm re-runs full authz (grant + host method security + row policy: the target row
  must be visible to the user) and re-validation at apply time.
- Editable fields limited to writable, non-sensitive attributes within the user's clearance.
- Approval policy for data writes (extends SEC-01 §6): e.g. RESTRICTED entity or BULK > 10
  ⇒ second approver; DELETE ⇒ approver by default.
- Proposal payloads stored encrypted-at-rest option; retention short (default 7 days after terminal state), audit keeps hashes.

## 10. Failure modes
| Failure | Behavior |
|---------|----------|
| Row changed since proposal | `CONFLICT`; UI offers "refresh proposal" (new before-snapshot, re-review) |
| Host validation/business exception | `FAILED` with sanitized message; tx rolled back; agent informed |
| Double-click confirm / retry | Idempotency key → same result |
| Crash during APPLYING | Host tx atomic; on restart proposals stuck in APPLYING reconciled: check `VersioningAdapter` for revision → APPLIED else FAILED |
| Proposal expired | 410 Gone problem `proposal-expired` |
| Versioning adapter missing for entity | Fallback `RowHashAdapter`; ENTITY_WRITE for RESTRICTED entities refused without real versioning |

## 11. Data model (`dai_*`)
| Table | Columns |
|-------|---------|
| `dai_change_proposal` | id, workspace_id, conversation_id, origin, owner_subject, target_json, kind, state, approval, content_hash, expires_at, created_at, applied_at, host_revision_ref, failure_code |
| `dai_change_proposal_record` | proposal_id, seq, entity_id, before_json (masked), after_json, base_version_kind, base_version_value |
| `dai_change_proposal_event` | proposal_id, at, actor, from_state, to_state, details_json |

## 12. Configuration
| Property | Default |
|----------|---------|
| `dynamic.ai.agent.write.enabled` | `false` (implemented) |
| `dynamic.ai.agent.write.proposal-ttl` | `15m` (implemented, 1m..30d) |
| `dynamic.ai.agent.write.max-records-per-proposal` | `100` |
| `dynamic.ai.agent.write.entity-write.enabled` | `false` (HOST_OPERATION only) |
| `dynamic.ai.agent.write.require-recent-auth` | `PT0S` (off) |
| `dynamic.ai.agent.write.versioning.tables.*` | — (history table mappings) |
| `dynamic.ai.agent.write.retention` | `7d` (implemented) |
| `dynamic.ai.agent.write.require-approver` | `false` (implemented; a delete always needs an approver) |
| `dynamic.ai.agent.write.max-concurrent-applies` | `8` (implemented, 1..100; node-local bulkhead) |

## 13. Observability
Counter `dynamic.ai.agent.proposals{state,kind,origin}`, timer propose→confirm latency,
conflict rate; spans `dai.proposal.create`, `dai.proposal.apply`; audit events
`PROPOSAL_CREATED/EDITED/CONFIRMED/APPROVED/REJECTED/APPLIED/CONFLICT/FAILED`.

## 14. Test strategy
Sample host with (a) Envers, (b) custom history table + trigger, (c) `@Version` only; tests
prove host history rows carry the **confirming user**; conflict tests (concurrent host
edit); injection tests (model tries to confirm/alter proposal); idempotent confirm;
crash-recovery reconciliation; Web Component accessibility (axe) and visual tests.

## 15. Open questions
OQ-14 (host versioning inventory), OQ-15 (web components vs React library), OQ-16 (ENTITY_WRITE in v1?).
