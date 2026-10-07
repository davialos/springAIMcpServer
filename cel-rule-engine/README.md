# CEL Rule Engine

A multi-tenant rule engine on **Google CEL** (`dev.cel:cel`) for Spring Boot 4 / Java 25 and PostgreSQL. Any application
asks it a question from a *trigger point* (a form, an action, optionally a field); the engine evaluates the rule groups
bound to that point by their *evaluation policy* and answers with the final messages (in the caller's language), one
action — **ALLOW**, **WARN** or **BLOCK** — and, on request, every rule's raw result. Channels (e-mail, push, API call)
can communicate an outcome.

```
caller ──POST /api/v1/evaluate──▶ trigger point ──▶ rule groups ──▶ policy ──▶ rules (CEL) ──▶ messages + action
                                                                                   ▲
                                              parameter library (customer.age …) ┘   cached in the application
```

## Run it locally

```bash
docker compose up -d            # PostgreSQL 17 (ruleengine / ruleengine)
mvn spring-boot:run             # Flyway creates the schema and loads the demo data (tenant ACME)
mvn test                        # 32 tests: CEL, policies, environment guard + integration tests (Docker needed)
```

```bash
curl -s localhost:8080/api/v1/evaluate -H 'Content-Type: application/json' -H 'X-Tenant-Code: ACME' -d '{
  "module": "LENDING",
  "trigger": { "form": "LOAN_APPLICATION", "action": "SUBMIT" },
  "language": "hi",
  "detailed": true,
  "context": {
    "customer": { "age": 17, "kycStatus": "PENDING", "creditScore": 720, "income": 80000, "email": "jo@acme.test" },
    "loan":     { "principal": 200000 }
  }
}'
```

Answer (simple view): `result`, `action` (`BLOCK`), `allowed` (`false`), `messages[]` (each failing rule's Hindi message, then
the composite message "आवेदक इस ऋण के लिए पात्र नहीं है।"); `detailed: true` adds `groups[]` with every rule's raw result
(`result`, `error`, `selected`, `micros`) and the channel `dispatches[]`. `dryRun: true` evaluates without writing the
audit log or calling channels.

## Concepts

| Concept | Tables | What it is |
|---|---|---|
| **Tenant / organization** | `re_tenant`, `re_organization` | Rules and groups belong to a tenant; an organization-level group of the same code overrides the tenant-wide one (header `X-Organization-Code`). |
| **Module** | `re_module`, `re_tenant_module` | A product area (LENDING, PAYMENTS …) a tenant selects; rules, groups and triggers belong to one module. |
| **Parameter library** | `sys_object`, `sys_object_attribute` | A *sys object* is a CEL variable, an *attribute* is `object.attribute` — `customer.age`, `transaction.amount`. Typed (STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, lists). **Cached** in the application (`ParameterLibraryService`): loaded at start-up, reloaded when its fingerprint in the database changes (every `ruleengine.library-refresh`) and immediately after any change through the admin API. |
| **Rule** | `re_rule` | A CEL expression that must yield a boolean, plus a message bundle and an action for **true** and for **false**. Compiled when saved: syntax, boolean result, unknown objects and unknown attributes are rejected (422). |
| **Rule group** | `re_rule_group`, `re_rule_group_member` | Rules with a *sequence*, an *evaluation policy*, and the group's own true/false message and action. |
| **Message bundle** | `sys_bundle`, `sys_bundle_text` | One message, one text per language (`en`, `hi`, `th` …). `{customer.age}` placeholders are filled from the request context. Language order: requested → tenant default → `en` → whatever exists. |
| **Trigger point** | `re_trigger_point`, `re_trigger_binding` | Where an application asks: a *form* + *action* (SUBMIT, APPROVE, ADD, BUY …) + optional *field*; bound to rule groups in sequence. |
| **Channels** | `re_email_template`, `re_api_endpoint`, `re_channel`, `re_action_binding` | E-mail (caller-side template id + name), push, API call; an *action binding* says "when this rule/group is TRUE/FALSE, use that channel". |
| **Audit** | `re_evaluation_log`, `re_dispatch_log` | Every evaluation (outcomes only; attribute **names**, never values) and every channel attempt. |

### Evaluation policies

| Policy | Runs | Group result | Reported messages |
|---|---|---|---|
| `FIRST_MATCH` (`matchOn` TRUE/FALSE) | rules in sequence, **stops** at the first whose result equals `matchOn` | `matchOn` if one matched, else the opposite | that rule's message, then the group message |
| `ALL_MATCH` (`matchOn`) | every rule | `matchOn` if any matched, else the opposite | every rule whose result equals `matchOn`, then the group message |
| `EVALUATE_ALL` | every rule | all true | every rule's message — true and false — then the group message |
| `COMPOSITE` (`ALL_TRUE` / `ANY_TRUE`) | every rule | one combined result | **true**: only the group's true message · **false**: the failing rules' messages, then the group's false message |

The action of a group is the strictest (BLOCK > WARN > ALLOW) of the reported rules' actions and the group's action for its
result; the final action is the strictest over all groups. A rule that cannot be evaluated (a missing attribute, a type
error) counts as **false** (`onError: AS_FALSE`, fail-safe, the error is in the raw result) or is left out (`SKIP`).

## Calling the engine from an application

* **Trigger point** — `{"trigger": {"type": "FORM", "form": "FUNDS_TRANSFER", "action": "CHANGE", "field": "amount"}}`
  finds the groups bound to that exact form/action/field; or name groups directly: `{"groups": ["TXN_CHECKS"]}`.
* **Context** — `{"customer": {"age": 20}}`: values are converted to the library's types (an integer sent for a decimal
  attribute, a date string for a DATE …), so `transaction.amount >= 50000` works whatever the JSON numbers were.
* **Headers** — `X-Tenant-Code` (required), `X-Organization-Code` (optional). Authentication is not part of this module:
  put it behind your gateway or add Spring Security.

## Channels

* **E-mail** — the template lives on the caller's side. A channel names the *template id* (what the caller's mail service
  knows) and the *name* (shown in the UI); the engine sends `{templateId, templateName, to, language, variables}`
  to `ruleengine.channels.email-gateway-url` (without it the message is recorded only). Recipients: `config.recipients`
  and/or the context value at `config.recipientPath` (`customer.email`).
* **Push** — topic from `config.topic` or `config.topicPath`; the first message is the body; `push-gateway-url` as above.
* **API** — a call to a registered API with the outcome as JSON. **Environment guard**: the deployment is DEV, QA or PROD
  (`ruleengine.environment`); `ruleengine.api-hosts.<env>` lists the hosts of each environment.
  * a host of this environment → accepted;
  * a host of **another** environment → refused (a DEV system never calls QA or production);
  * a host in **no** list is *external*: the first request answers **409** with `confirmationRequired: true` and the text
    *"You are trying to integrate an external API that we cannot validate as a production or same-environment API.
    Please confirm the API once again and proceed with this action."* — the UI shows it in a pop-up and repeats the
    request with `confirmExternal: true` (who confirmed and when is stored). `GET /api/v1/admin/api-endpoints/check?url=…`
    answers the same question without saving.
  * the guard is applied again when the API is called.

The seeded "Risk webhook" calls this application's own `POST /hooks/rule-events` (`GET` lists what it received).

## Admin API (`/api/v1/admin`, header `X-Tenant-Code`)

| Area | Endpoints |
|---|---|
| Library | `GET /library`, `POST /library/refresh`, `POST /library/objects`, `PUT /library/objects/{code}`, `POST /library/objects/{code}/attributes`, `DELETE …/attributes/{attribute}` |
| Bundles | `GET/POST /bundles`, `GET /bundles/{code}`, `PUT /bundles/{code}/texts/{language}` |
| Rules | `GET/POST /rules`, `GET/PUT /rules/{id}`, `POST /rules/validate`, `POST /rules/{id}/test` |
| Groups | `GET/POST /groups`, `GET/PUT /groups/{id}`, `GET/PUT /groups/{id}/members`, `DELETE /groups/{id}/members/{rule}` |
| Triggers | `GET/POST /triggers`, `GET/PUT /triggers/{code}/groups`, `DELETE /triggers/{code}/groups/{group}` |
| Channels | `/email-templates`, `/api-endpoints` (+ `/check`), `/channels`, `/action-bindings` |
| Tenants | `/tenants`, `/organizations`, `/modules`, `/tenant-modules`, `/languages` |
| Audit | `GET /api/v1/evaluations`, `GET /api/v1/evaluations/{id}/dispatches` |

Errors are RFC 9457 problem details with a `code` (`INVALID_EXPRESSION` + `problems[]`, `VALIDATION_FAILED`,
`MODULE_NOT_ENABLED`, `EXTERNAL_API_CONFIRMATION_REQUIRED`, `NOT_FOUND` …).

## Demo data (`db/seed`)

Tenant **ACME** with modules LENDING and PAYMENTS, languages en/hi/th/es, objects `customer`, `loan`, `account`,
`transaction`, 9 rules, one group per policy and trigger points for loan applications and funds transfers:

| Trigger | Groups |
|---|---|
| `LOAN_APPLICATION` / `SUBMIT` | `LOAN_ELIGIBILITY` (COMPOSITE) + `LOAN_ADVISORY` (EVALUATE_ALL) |
| `FUNDS_TRANSFER` / `SUBMIT` | `TXN_CHECKS` (FIRST_MATCH on FALSE) + `TXN_RISK_FLAGS` (ALL_MATCH on TRUE) |
| `FUNDS_TRANSFER` / `CHANGE` on field `amount` | `TXN_CHECKS` |

For an empty production database remove `classpath:db/seed` from `spring.flyway.locations`.

## Layout

```
db/migration/V1__schema.sql        schema (sys_*, re_*)             db/seed/V100__reference_data.sql   demo data
library/   ParameterLibrary(+Service cache) · CelEngine (compile, validate, evaluate) · ContextCoercer
eval/      EvaluationService · PolicyStrategy (the four policies) · RuleEvaluator · MessageResolver · Results
channel/   EnvironmentGuard · ApiEndpointService · Dispatchers (Email/Push/Api) · Gateways · ChannelService
repo/      JdbcClient repositories      api/   controllers, problem details, tenant resolution
```

Not included (decisions for you): authentication/authorization of the admin and evaluation APIs, a UI (the API returns what
the pop-up needs), more trigger types than `FORM`, rule versioning/approval workflow (rules carry a `version` counter and a
DRAFT/ACTIVE/INACTIVE status).
