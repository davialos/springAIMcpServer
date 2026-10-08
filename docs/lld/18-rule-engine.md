# LLD-18: CEL rule engine

Status: implemented (runtime + schema). Module `spring-ai-mcp-server-common-ruleengine`, migration `V11__rule_engine.sql`,
auto-configuration `DaiRuleEngineAutoConfiguration`, decision ADR-0025.

## 1. Purpose & responsibilities
Evaluate tenant-owned business rules, written as Google CEL boolean expressions over a **parameter library**, for any
application that integrates the starter. An application sends *facts* (values for library parameters) and a *rule
group* (or a trigger point such as "form LOAN_APPLICATION, action SUBMIT"); it receives a **decision**
(`ALLOW` / `WARN` / `BLOCK`), the **localized messages** the group's policy says to show, optionally the raw per-rule
results, and the **communications** that fired (e-mail, push, API call).

Authoring is a REST API with a draft → review → publish lifecycle (§13, §14); the UI that sits on it is not part of
this document (OQ-74). Out of scope: a group-level test bench (OQ-75).

## 2. Data model (tables `dai_re_*`, V11)

| Table | Purpose |
|-------|---------|
| `dai_re_module` | Business module (LOAN, ORDERS…) rules are selected by. Platform-wide. |
| `dai_re_sys_object` | **sysObject** — a named business object (`customer`, `loan`); its `code` is a CEL identifier. |
| `dai_re_sys_object_attribute` | **sysObjectAttribute** — `age : INT`, `amount : DOUBLE`… `object.code + '.' + attribute.code` is the CEL variable. Types: STRING INT DOUBLE BOOL TIMESTAMP DURATION LIST_STRING LIST_INT LIST_DOUBLE MAP ANY. |
| `dai_re_parameter_v` (view) | One row per active CEL variable (`cel_name = customer.age`) — what the cache loads. |
| `dai_re_sys_bundle` / `dai_re_sys_bundle_message` | **sysBundle** — a message identity and one text per language (`en`, `hi`, `th`, `pt-BR`…). |
| `dai_re_rule` | A CEL expression (≤ 8192 chars), status DRAFT/ACTIVE/RETIRED, true/false **message bundle** and true/false **action** (ALLOW/WARN/BLOCK). Tenant + optional organization + module. |
| `dai_re_rule_parameter` | Parameters a rule reads (impact analysis), maintained by the admin API from the compiled expression. |
| `dai_re_rule_group` | **Rule group**: `evaluation_policy`, `match_on`, composite messages/actions, `on_error`. Tenant + optional organization + module. |
| `dai_re_rule_group_rule` | Group membership with `sequence` and `enabled`; a rule may sit in many groups. |
| `dai_re_email_template` | Caller-side e-mail template: `template_ref` (the id in the caller's mail system) + `name`, shown together in the UI. |
| `dai_re_api_endpoint` | HTTP endpoint with `environment` DEV/QA/PROD/EXTERNAL; EXTERNAL requires `external_confirmed_by/at` (CHECK). Secrets are referenced (`auth_secret_ref`), never stored. |
| `dai_re_outcome_channel` | "When rule/group X yields TRUE/FALSE/ERROR/ANY → EMAIL (template) / PUSH (title+body bundles) / API (endpoint)", with a CEL `recipient_expression` (e.g. `customer.email`). CHECK keeps the columns consistent with the channel type. |
| `dai_re_trigger_point` | Binds `(application, form, action[, field])` to a rule group: `FORM_ACTION` (SUBMIT, APPROVE, ADD, BUY…) and `FORM_FIELD` (ON_CHANGE of one field). Several groups per trigger run in `sequence` order. |
| `dai_re_evaluation` / `dai_re_evaluation_result` | Value-free log: group, policy, decision, language, duration, and per rule outcome/action/error code. **Monthly range partitions** on `evaluated_at` (V14), registered in `dai_partitioned_table` (13 months retention, V14) so the existing maintenance job creates future partitions and drops old ones; override with `dynamic.ai.agent.store.retention.dai_re_evaluation`. |
| `dai_re_revision` | Revision history of every rule and group (V14): `kind` RULE/GROUP, `revision_no`, `state` DRAFT/SUBMITTED/APPROVED/REJECTED/PUBLISHED/SUPERSEDED, the whole definition as `content jsonb`, `rollback_of`, who created/submitted/reviewed/published and when. At most one open revision per subject (partial unique index); a CHECK forbids `reviewed_by = submitted_by`. |
| `dai_re_dispatch` | Delivery outbox (V14): `channel_type`, `payload jsonb`, `status` PENDING/DELIVERED/DEAD, `attempts`/`max_attempts`, `next_attempt_at`, lease (`locked_until`, `locked_by`), `last_error` (a short code). |
| `dai_re_change_marker` | One row per scope (PARAMETERS, BUNDLES, RULES), bumped by statement triggers on every write. |

Tenancy: `tenant_id` and `organization_id` are opaque host ids (ADR-0005, no foreign keys). `organization_id NULL` =
"all organizations of the tenant"; an organization's own group with the same code **shadows** the tenant-wide one.
The parameter library and bundles are platform-wide; everything else is tenant-owned.

## 3. Evaluation policies

Rules of a group run in `sequence` order. Per rule the outcome is `TRUE`, `FALSE` or `ERROR`. The action of a result is
the rule's `true_action` / `false_action`, or the group's `on_error` for `ERROR`.

| Policy | What runs | What is reported (`selected`) | Decision |
|--------|-----------|-------------------------------|----------|
| `FIRST_MATCH` | Stops at the first rule whose result equals `match_on` | That one rule | Its action (ALLOW if nothing matched) |
| `ALL_MATCH` | Every rule | All rules whose result equals `match_on` | Strictest action of the reported rules |
| `EVALUATE_ALL` | Every rule | Every rule, true and false | Strictest action of all |
| `COMPOSITE` | Every rule; all must be TRUE | The failing rules, plus one **group message** (true bundle if all TRUE, else false bundle) | `composite_true_action` if all TRUE, else `composite_false_action` |

`match_on = TRUE` ("the first rule that holds") or `FALSE` ("the first rule that fails"). An `ERROR` counts as *not
true*: it matches `FALSE`, and fails a COMPOSITE group. Under every policy an `ERROR` also contributes its action to the
decision, so an unevaluable rule is never silently skipped. Error codes: `COMPILE_ERROR`, `MISSING_PARAMETER`,
`INVALID_PARAMETER`, `NOT_BOOLEAN`, `EVALUATION_ERROR`; details name the parameter, never a value.

## 4. Response composition

`ResponseComposer` turns the raw `GroupResult` into the caller's `EvaluationResponse`: `decision`, `matched`,
`messages` (policy-shaped, in order: COMPOSITE puts the group message first), `primaryMessage` (the one to show if the
UI shows one: the group message for COMPOSITE, otherwise the most severe), and `results` (raw, only with
`ResponseDetail.WITH_RAW`). A rule or outcome without a message bundle is silent.
Language: each requested tag exactly → its primary language (`pt-BR` → `pt`) → the configured default (`en`) → the
alphabetically first available text.

## 5. Parameter library, CEL and caching
- `ParameterLibrary` declares every active attribute as a typed CEL variable (`addVar("customer.age", INT)`) in two CEL
  environments (result type bool for rules, string for recipient expressions). `compileBoolean` therefore rejects
  syntax errors, unknown parameters, type errors and non-boolean results **at save time**; `CompiledExpression.referenced()`
  lists the parameters used.
- Facts may be flat (`"customer.age": 34`) or nested (`customer: {age: 34}`); `Facts` coerces to the declared type per
  evaluation and only for referenced parameters (ints widen to double; timestamps/durations accept ISO-8601 strings).
- `RuleCatalogCache` (per node, immutable snapshots) polls the three `dai_re_change_marker` rows at most once per
  `poll-interval` per tenant and reloads only what moved. Rules compile lazily once per snapshot. If the store is down the
  last snapshot is served (fail the feature, not the host); a tenant never loaded throws `RuleCatalogUnavailableException`.
  Memory is bounded by `max-tenants`.
- Limits: expression ≤ 8192 chars, parse depth 64, comprehension iterations ≤ 10 000.

## 6. Communications
`ChannelPlanner` resolves the bindings that fire (rule bindings per rule result; group bindings on the group outcome —
ERROR if any rule errored, else FALSE if any evaluated rule was false, else TRUE; a binding on `ERROR` fires whenever any
rule errored) into `PlannedChannel`s with the recipient (CEL expression over the facts), localized push texts and the
e-mail template / endpoint. `ChannelDispatcher` sends them through ports and isolates failures:
- **E-mail** — `EmailSender` (host bean): `EmailMessage(templateRef, templateName, recipient, language, module, group, decision)`.
  The template id and name come from `dai_re_email_template` so the UI can show both.
- **Push** — `PushSender` (host bean): localized title/body from bundles.
- **API** — `ApiCaller` (default `HttpApiCaller`, JDK client, endpoint timeout, no redirects) with a JSON body holding the
  decision and messages (never the input values).
- A missing port means `SKIPPED`, not an error. Recipients and payloads are never logged.

**API environment guard** (`ApiEnvironmentPolicy`): tier DEV → DEV endpoints; TEST/STAGE → QA; PROD/UNKNOWN → PROD
(LLD-12). A mismatched endpoint is `REFUSED`. An `EXTERNAL` endpoint cannot be validated: at configuration time
`checkForSave(EXTERNAL, confirmed=false)` returns `CONFIRMATION_REQUIRED` with the pop-up text ("You are trying to
integrate an external API that we cannot validate… please confirm and proceed"); the confirmation is stored with the
endpoint and an unconfirmed one is never called. `ApiEnvironmentClassifier` pre-selects DEV/QA/PROD from host glob
patterns and answers `EXTERNAL` when nothing, or more than one environment, matches.

### 6.1 Delivery: direct or outbox (OQ-67)
`dynamic.ai.agent.rule-engine.delivery`:
- `IMMEDIATE` (default): `ChannelDispatcher` sends on the caller's thread; a failure is reported in `dispatched` and not retried.
- `OUTBOX`: `OutboxChannelDelivery` writes one `dai_re_dispatch` row per communication (status `QUEUED` in the response) and
  `OutboxWorker` — a daemon loop on every node — delivers them. Rows are claimed with `FOR UPDATE SKIP LOCKED` and a lease
  (`attempts` is incremented at claim, so a crash mid-send still counts); a node that dies leaves a lease that expires.
  Failure → back to PENDING after `base-delay × 2^(attempt−1)` (cap `max-delay`, ±20 % jitter); after `max-attempts`, or at once
  for a permanent failure (endpoint removed, **environment guard refusal — checked again at send time**), the row is DEAD.
  Delivery is **at-least-once**: API bodies carry `dispatchId` so receivers can de-duplicate.
  **All time comparisons use the database clock** (`now()`), so clock skew between nodes cannot make a row early, late or
  double-claimed. The recipient is scrubbed (`payload = '{}'`) when a row is delivered; DELIVERED rows are purged after
  `delivered-retention` (1 d) and DEAD rows after `dead-retention` (14 d). Operators list dead rows
  (`GET …/deliveries/dead`, no payloads) and retry them. Errors are stored as short codes only (never a message that
  could carry the recipient).
- Not atomic with the caller's own transaction: the row is written after the evaluation. A caller that needs "decision and
  notification or neither" should call the engine inside its transaction using its own `ChannelDelivery`.

## 7. Trigger points and integration
```java
// "the user pressed SUBMIT on form LOAN_APPLICATION"
TriggerResult r = ruleEngine.evaluate(new TriggerRequest(tenantId, orgId, "loan-portal", TriggerType.FORM_ACTION,
        "LOAN_APPLICATION", "SUBMIT", null, facts, List.of("hi", "en")), ResponseDetail.MESSAGES, /*dispatch*/ true);
if (r.decision() == Action.BLOCK) { /* show r.groups().get(0).response().messages() */ }
```
`FORM_FIELD` triggers (`ON_CHANGE` of `amount`) work the same with `fieldCode`. `RuleEngine.evaluate(EvaluationRequest, …)`
evaluates one group by `(module, group code)` directly. Nothing bound to a trigger = `ALLOW`. The **application enforces**
the decision; the engine only decides.

## 8. Configuration
```
dynamic.ai.agent.rule-engine.enabled=true          # default false
dynamic.ai.agent.rule-engine.poll-interval=10s     # 1s..10m
dynamic.ai.agent.rule-engine.max-tenants=500
dynamic.ai.agent.rule-engine.default-language=en
dynamic.ai.agent.rule-engine.record-evaluations=true
dynamic.ai.agent.rule-engine.require-review=true   # a second person must approve before publish
dynamic.ai.agent.rule-engine.delivery=IMMEDIATE    # or OUTBOX
dynamic.ai.agent.rule-engine.outbox.poll-interval=5s      # 1s..5m
dynamic.ai.agent.rule-engine.outbox.batch-size=20         # 1..500
dynamic.ai.agent.rule-engine.outbox.max-attempts=6        # 1..50
dynamic.ai.agent.rule-engine.outbox.lease=2m              # 10s..1h
dynamic.ai.agent.rule-engine.outbox.base-delay=10s        # doubles per attempt
dynamic.ai.agent.rule-engine.outbox.max-delay=1h
dynamic.ai.agent.rule-engine.outbox.delivered-retention=1d
dynamic.ai.agent.rule-engine.outbox.dead-retention=14d
dynamic.ai.agent.rule-engine.api-hosts.dev=localhost,127.0.0.1      # host globs; * allowed
dynamic.ai.agent.rule-engine.api-hosts.qa=*.qa.acme.com
dynamic.ai.agent.rule-engine.api-hosts.prod=api.acme.com
```
Host beans: `EmailSender`, `PushSender` (optional); every library bean is `@ConditionalOnMissingBean`.
Dependency: add `spring-ai-mcp-server-common-ruleengine` (not part of the default starter).

## 9. Failure modes & security
- Store down → serve last snapshot; never loaded → engine call fails, host unaffected.
- Rule no longer compiles after a parameter change → that rule is `ERROR/COMPILE_ERROR` (group `on_error`), others run.
- No values in `dai_re_evaluation*`, logs, error details or API payloads; recipients are personal data and never logged.
- CEL is side-effect free; no custom functions are registered, no reflection, no host beans reachable.
- Default deny: nothing is exposed over HTTP by this change; authoring endpoints must be authorized by the host's
  access management when added.

## 10. Local development
`scripts/rule-engine/local-db.sh start` — throw-away PostgreSQL 16 (no Docker): applies V1…V11 and
`scripts/rule-engine/sample-data.sql` (loan tenant: 3 objects/9 attributes, 5 rules, 4 groups — one per policy —
messages in en/hi/th, an e-mail template, DEV/QA/PROD endpoints, channels, 3 trigger points).
`DAI_RULE_IT_JDBC_URL=jdbc:postgresql://localhost:54329/dai mvn -pl spring-ai-mcp-server-common-ruleengine verify`
runs the integration tests against it (without the variable they use Testcontainers).

## 11. Open points
OQ-69 (message placeholders), OQ-70 (console design), OQ-71 (dev-only auth service), OQ-74 (UI on the starter's authoring API,
§13), OQ-75 (group-level test bench), OQ-76 (promoting rules to PROD without the authoring override), OQ-77 (outbox atomic with the
caller's transaction), OQ-78 (the ecosystem service writes rules and groups directly and bypasses the review lifecycle of §14).
Resolved by §13, §14 and §6.1: OQ-65, OQ-66, OQ-67, OQ-68.

## 12. Authoring API, console and logs (ecosystem, ADR-0026)
Implemented outside the library in `docker/rule-engine/` (not part of the starter). Runbook:
[integration/rule-engine-ecosystem.md](../integration/rule-engine-ecosystem.md).

### 12.1 Services
| Service | Port | Role |
|---------|------|------|
| `auth-service` | 8091 | Users, tenants, organizations (`re_auth_*`); `POST /auth/login` (protobuf or JSON); signs the access token |
| `rule-engine-service` | 8092 | Resource server over the library: setup, authoring, evaluation, admin logs; runs V1–V14 migrations |
| `ui` (nginx) | 8080 | Console; proxies `/auth` and `/api` (one origin) |

### 12.2 Identity and scope
Token claims `tenant_id`, `org_id` (absent = tenant-wide user), `role` (`USER`/`ADMIN`) are the only source of scope. Evaluation
and authoring statements always carry `tenant_id` and the organization visibility predicate (own org or tenant-wide). Cross-tenant
ids answer 404, never 403.

### 12.3 API (`/api/v1`, bearer token)
Read (USER, ADMIN): `GET /me /setup /modules /library /rules /rules/{id} /rule-groups /rule-groups/{id} /triggers /channels
/email-templates /api-endpoints`. Write: `POST /rules`, `PATCH /rules/{id}/status`, `POST /rule-groups`, `PUT /rule-groups/{id}`,
`PATCH /rule-groups/{id}/status`. Evaluate: `POST /evaluations`, `POST /triggers/evaluate` (facts capped at 200, body at 256 KB).
Admin only (`/admin/logs`): `GET /summary /evaluations /evaluations/{id} /audit /conversations /conversations/{id}`.
Errors are RFC 7807 problem details; a CEL error at save time is `422` with the compiler message (no values).

### 12.4 Audit trail (V12 `dai_re_audit_log`)
One row per authoring write and per evaluation requested through the API: actor id/name/role, scope, `action`, entity type/id/code,
summary and a `details` JSON of counts, codes and ids. No fact values, no message texts. Written in the change's transaction.

### 12.5 Observability
JSON console logs → Promtail → Loki (`service`, `level` labels); `/actuator/prometheus` → Prometheus (request histograms, JVM, Hikari);
PostgreSQL log tables → Grafana through role `grafana_ro` (grants in `docker/rule-engine/seed/grants.sql`). Dashboard
`rule-engine-ops` is generated by `observability/grafana/build_dashboard.py`; `dev.sh dashboards` executes all its queries.

### 12.6 Limits and local-only choices
Channels are read-only in the console; e-mail/push senders are not wired. Lockout per username can be abused to lock a known
account. Promtail mounts `/var/run/docker.sock`; the seeded passwords are shown on the login page of the local build. None of
this belongs in a shared environment.

### 12.7 Relationship to the starter's authoring API (§13–§14)
Two front ends exist over the same `dai_re_*` tables. The ecosystem service of this section is a standalone deployable with its
own token claims and writes rules/groups directly (create, status change), so a rule it creates has **no revision history** and no
`published_revision_id`. The starter's authoring API (§13) goes through `RuleLifecycle`: drafts, four-eyes review, publish, rollback.
Mixing them on one database is safe (the lifecycle starts a revision history the first time it edits such a rule) but the review
rules of §14 only apply to changes made through §13 (OQ-78).

## 13. Authoring API of the starter (OQ-65)
Base path `/dynamic-ai/admin/api/v1`. **The workspace is the tenant**: workspace-scoped calls live under
`/workspaces/{workspaceId}/rule-engine/…` and need the permission *in that workspace*; another workspace's ids are 404.

| Permission | Held by (default bundles) | Allows |
|---|---|---|
| `rules:read` | platform admin, workspace owner, author, approver | everything that reads (lists, details, revisions, log, dead deliveries, expression validation) |
| `rules:author` | platform admin, workspace owner, author | drafts, submit, withdraw, bundles, e-mail templates, API endpoints, channels, triggers, expression test bench |
| `rules:publish` | platform admin, workspace owner, approver | approve, reject, publish, rollback, retire, retry dead deliveries |
| `rules:library` | platform admin (global only) | modules, sys objects/attributes, platform bundles |

**Writes also need the AUTHORING capability of the environment** (LLD-12): off in PROD unless the audited, time-boxed
production override is on; reads stay available in every tier. (Promotion of rules to PROD without the override is
OQ-76.) Every write is audited (`RULE_*`, `GROUP_*`, `RULES_*`).

| Endpoint (under the workspace base) | |
|---|---|
| `GET/POST /rules`, `GET /rules/{id}`, `PUT /rules/{id}/draft`; same for `/groups` | list, create (draft), detail with open and published revision, save draft |
| `POST /{rules\|groups}/{id}/submit · withdraw · approve · reject · publish · rollback · retire`, `GET …/revisions` | lifecycle (§14) |
| `POST /expressions/validate`, `POST /expressions/test` | compile against the library (issues + parameters read); run on sample values (nothing stored) |
| `GET /library` | modules and sys objects with attributes (for the editor) |
| `GET/POST /bundles`, `PUT/DELETE /bundles/{id}/texts/{language}`, `DELETE /bundles/{id}` | workspace messages; platform bundles are read-only here |
| `GET/POST/PUT /email-templates` | caller-side template id + name |
| `GET/POST/PUT /api-endpoints`, `POST /api-endpoints/check` | endpoints; `check` returns the environment, verdict and pop-up text |
| `GET/POST/PUT/DELETE /channels`, `/triggers` | outcome channels, trigger points (validated against owner, module, template, endpoint, recipient expression) |
| `GET /evaluations`, `GET /deliveries/dead`, `POST /deliveries/{id}/retry` | evaluation log, dead letters |
| `/rule-library/**` (global) | modules, objects, attributes (+ `GET …/attributes/{id}/usage`), platform bundles |

Errors are RFC 9457 problems; the **title is a stable code**: 404 `not_found`; 409 `four_eyes`, `revision_locked`,
`invalid_state`, `revision_open`, `rule_in_use` (detail lists the groups), `parameter_in_use` (lists the rules),
`duplicate_code`, `duplicate`, `bundle_in_use`, `last_text`, `confirmation_required`; 400 with one field violation per problem
for invalid content (`expression: undeclared reference to …`, `members: …`) and `api_environment_mismatch`.

## 14. Rule and group lifecycle (OQ-66)
```
DRAFT --submit--> SUBMITTED --approve (not the submitter)--> APPROVED --publish--> PUBLISHED  (previous → SUPERSEDED)
  ^                   |  \--reject (comment required)--> REJECTED --edit--> DRAFT
  \-----withdraw------/
```
- The live rows (`dai_re_rule`, `dai_re_rule_group` + members) hold only **published** content and are the only thing the engine
  reads; a draft never affects evaluation. Editing a live rule opens the next revision; the old one keeps running until the new
  one is published. Publishing updates the live row, the rule's parameter links and a group's members **in one transaction**.
- **Four eyes**: the submitter cannot approve or reject (service check + `ck_re_revision_reviewer`). With `require-review=false`
  an author may publish a DRAFT directly (small teams, DEV).
- **Validated three times**: when saved (expression compiles to bool against the current library, bundles exist, sequences
  unique, composite messages only on COMPOSITE), when submitted and when published (the library may have changed; a group's
  member rules must be published, belong to the group's module and tenant). Invalid content is a 400, nothing is saved.
- **Rollback** copies an older PUBLISHED/SUPERSEDED revision into a new DRAFT (`rollback_of`) that goes through the same steps.
- **Retire** takes a rule or group out of service; a rule in an active group is refused (`rule_in_use`). Library parameters
  that an active rule reads cannot be retyped or deactivated (`parameter_in_use`).
- Every statement is scoped by tenant and the subject row is locked while it changes.

## 15. AI assistant and the Angular console (ADR-0028)
- **Endpoint:** `GET /api/v1/assistant` → `{available, agentSlug, provider, note}`; it provisions the tenant's workspace, tool
  bindings and agent and the caller's grants on first use. The chat is the library's own
  `POST /dynamic-ai/api/agents/{slug}/chat/stream` (SSE events `turn.start`, `text.delta`, `usage`, `turn.end`, `error`) with the
  same bearer token. `POST /api/v1/expressions/check` compiles an expression for live validation.
- **Tools:** six read-only operations over the caller's visible rules, groups and the parameter library, plus a CEL check; results
  are bounded (25 items, expressions cut at 400 characters). They run as the caller, so scope is the token's.
- **Log:** conversations are recorded by the library (`conversations.enabled=true`, redacted) in the tenant's workspace and are
  read in the console's administration page and `GET /api/v1/admin/logs/conversations`.
- **Configuration:** `ASSISTANT_ENABLED`, `ASSISTANT_PROVIDER` (`offline` | `anthropic`), `ASSISTANT_MODEL_NAME`,
  `SPRING_AI_MODEL_CHAT`, `ANTHROPIC_API_KEY`, `ANTHROPIC_CHAT_MODEL`; `dynamic.ai.agent.*` as in the service's `application.yml`.
- **Consoles:** React on :8080 and Angular on :8081 speak the same API and contract; only the Angular console has the assistant.
