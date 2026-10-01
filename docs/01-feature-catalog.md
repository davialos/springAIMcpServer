# 01 — Feature Catalog

Owner: control-plane-designer · Status: Draft v1

## 1. Personas

| ID | Persona | Who in the host org | Primary goal |
|----|---------|---------------------|--------------|
| P1 | **Host Developer** | Engineer integrating the starter | Add JAR, annotate code, ship safely with zero surprises |
| P2 | **Platform Admin** | App owner / SRE / platform team | Enable features, connect IdP groups, set global limits & models |
| P3 | **Workspace Owner** | Team lead | Manage their team's agents/endpoints, members, budgets |
| P4 | **Author** | Developer / data analyst on a team | Build endpoints, queries, agents from the catalog |
| P5 | **Approver** | Senior engineer / data owner | Review & approve publishes touching sensitive data or writes |
| P6 | **Consumer** | End user (via host UI), other service, MCP client | Call endpoints, chat with agents |
| P7 | **Auditor** | Security / compliance | See who did what, prove controls work |

## 2. Release tiers
- **MVP (v1.0):** must-have to be production-usable for read-only use cases.
- **v1.x:** hardening & scale features after first production tenant.
- **v2:** strategic extensions.

## 3. Features

### A. Host integration (P1)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-01 | **Drop-in starter** — add one dependency, app boots with features OFF by default except catalog | MVP | No bean/path/table collision with a sample host; `dynamic.ai.agent.enabled=false` removes every bean |
| F-02 | **Opt-in semantic annotations** `@AiContext`, `@AiEntityProperty`, `@AiExposedAction(readOnly=true by default)`, `@AiParam`, `@AiQueryConstraints` — no build plugin | MVP | Only annotated elements appear; works identically with Maven, Gradle, IDE, Kotlin |
| F-03 | **Sensitive marking** `@AiEntityProperty(sensitive=true)`, `@AiParam(sensitive=true)`, plus `@JsonIgnore`/`@Transient` and a name heuristic | MVP | Sensitive members absent from catalog and masked in all outputs |
| F-04 | **Override any default** by declaring own bean (security, mapper, model, store) | MVP | Every default is `@ConditionalOnMissingBean`; documented SPI list |
| F-05 | **Catalog export & golden-file CI test** (build-tool agnostic) | MVP | CI fails on breaking changes (removed action, `readOnly` flipped, sensitivity lowered) |
| F-06 | **Test kit** (`@DynamicAiTest`, fake ChatModel, catalog fixtures) | v1.x | Host can test its agents without a live LLM |

### B. Code & data catalog (P3, P4)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-10 | **Catalog browser** — entities, attributes, relations, actions with their annotation descriptions and per-layer provenance | MVP | Searchable; shows host version and scan fingerprint |
| F-11 | **Entity relationship graph** view | MVP | Graph of exposed entities with cardinalities |
| F-12 | **Description overrides** — admins refine LLM-facing descriptions without code change | MVP | Override versioned; original annotation text preserved |
| F-17 | **Mandatory pagination** for every list-returning tool/query (`hasMore`, signed `nextCursor`) | MVP | No tool can return an unbounded list; forged cursors rejected |
| F-15 | **Policy JSON layer** (`ai-agent-policy.json`, classpath/file, per environment) to disable or re-describe actions, tighten limits | MVP | Can only restrict/re-describe, never expose; invalid file ⇒ AI tools disabled (fail closed) |
| F-16 | **AI write guard** — read actions run in a read-only scope; any write attempt is vetoed, audited, and repeated violations auto-disable the tool | MVP | Hibernate insert/update/delete during an AI read call is rejected in tests |
| F-13 | **Data classification tags** (PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED) per entity/attr | MVP | Tag drives ABAC & masking |
| F-14 | **Catalog drift detection** — published resources referencing removed/changed elements are flagged | v1.x | Startup report + UI banner; affected resources auto-suspended if configured |

### C. Dynamic endpoints (P4, P6)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-20 | **Endpoint builder** — path, method, params, backing action (query / agent / host method) | MVP | Published endpoint live on all nodes ≤ 30 s, under `/dynamic-ai/api/**` |
| F-21 | **Request validation** from declared param schema (JSON Schema) | MVP | Invalid input → RFC 9457 problem with field errors |
| F-22 | **Response shaping** — projection, field rename, masking, pagination envelope | MVP | Sensitive fields never serialized |
| F-23 | **OpenAPI 3.1 generation** of dynamic endpoints | MVP | `/dynamic-ai/api/openapi.json` reflects current published set |
| F-24 | **Per-endpoint rate limits & quotas** | MVP | 429 with `Retry-After` |
| F-25 | **Endpoint versioning** (`/v1`, `/v2`) & deprecation headers | v1.x | `Deprecation`/`Sunset` headers emitted |
| F-26 | **Response caching** (opt-in, per endpoint, principal-aware keys) | v1.x | Cache never shared across principals with different row filters |
| F-27 | **Write endpoints** (create/update/delete) — return a *change proposal* for user review, never write directly (LLD-11) | MVP | Write applied only after explicit confirm; host versioning/audit rows show the confirming user |
| F-28 | **Undo via history** — propose reverting a record to a previous host version | v1.x | Uses host versioning (Envers/history tables); still reviewed & confirmed |

### D. Dynamic queries (P4)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-30 | **Visual query builder** — entity, projections, filters, joins along declared relations, sort | MVP | Produces a query AST; no raw SQL/JPQL |
| F-31 | **Parameterized filters** bound from request/agent input | MVP | All values bound; injection tests pass |
| F-32 | **Query preview** with row limit & explain in a sandbox | MVP | Preview capped (e.g. 20 rows), runs as the author |
| F-33 | **Aggregations** (count, sum, avg, group by) | v1.x | |
| F-34 | **Row-level security policies** (predicate templates bound to principal attributes) | MVP | Policy applied to every query, including agent-invoked ones |
| F-35 | **Named native SQL (admin-only, reviewed)** for reporting DBs | v2 | Four-eyes approval, read-only connection |

### E. Agents (P4, P6)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-40 | **Agent studio** — system prompt, model, temperature, tools (host methods, queries, MCP), memory, RAG sources | MVP | Versioned agent definitions |
| F-41 | **Tool picker from catalog** — select annotated actions/queries; `intent` + `@AiParam` become the tool description | MVP | Tool schema generated from signature |
| F-42 | **Playground** — chat with draft agent, see tool-call trace, tokens, cost | MVP | Runs as the author, never in prod data unless allowed |
| F-43 | **Agent endpoint** — REST (sync) + SSE streaming chat API per agent with a typed event contract (LLD-13) | MVP | First token visible < 2 s p95 (model permitting); stream always terminates with `turn.end` or `error`; client disconnect cancels the model call |
| F-44 | **Conversation memory** per user/session, retention policy | MVP | Isolation per principal+agent; TTL purge |
| F-45 | **Reviewed writes (propose → review → confirm → apply)** for every mutating tool | MVP | Model can only create a proposal; confirm is an authenticated user HTTP request with content hash; optimistic version check; host versioning & audit tables record the change as the user |
| F-51 | **Display UI components** — agents pass structured data (table, record card, chart, timeline) to UI components | MVP | Payload schema-validated; rows must originate from this turn's tool results; masked by clearance |
| F-52 | **Review UI components** — record-diff, record-form, delete-confirm, bulk-change-table with before/after, version, history, validation | MVP | Server-generated from proposal; editable fields limited to writable attributes |
| F-53 | **Embeddable Web Components** (`<saimcp-*>`) for host UIs + JS client | MVP | Works in React/Angular/Thymeleaf hosts; WCAG 2.2 AA; themable via CSS variables |
| F-46 | **Structured output** agents (JSON schema responses) | v1.x | Validated with retry |
| F-47 | **RAG over host documents** (vector store integration) | v1.x | Document ACLs enforced at retrieval |
| F-48 | **Evaluation suites** — golden Q&A, tool-trajectory checks, run on publish | v1.x | Publish blocked if eval score < threshold |
| F-49 | **Model routing & fallback** (primary/secondary provider, per-agent) | v1.x | Failover on provider error/timeout |
| F-54 | **Parallel read tool calls** within a turn (bounded) | MVP | 4 × 200 ms reads complete in ≈ 250 ms |
| F-50 | **Multi-agent orchestration** (agent-as-tool) | v2 | Depth & budget bounded |

### F. MCP (P6)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-55 | **Expose published tools/agents as an MCP server** (Streamable HTTP; stateless option) | MVP (flag, default off) | OAuth 2.1 protected resource (RFC 9728 metadata, audience-bound tokens); approved-client registry; per-call scope checks; snake_case tool names; structured result envelope |
| F-56 | **Consume external MCP servers** as agent tools | v1.x | Allow-listed servers only; SSRF protection |

### G. Access management & governance (P2, P3, P5, P7)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-60 | **Use existing IdP/Spring Security** — no new logins | MVP | Works with host's OIDC/JWT/session/LDAP auth |
| F-61 | **Group → role mapping** UI (Entra/Okta/Keycloak groups, LDAP DNs, authorities) | MVP | Mapping versioned & audited |
| F-62 | **Workspaces (teams)** owning resources, with members & roles | MVP | Cross-workspace access denied by default |
| F-63 | **Fine-grained grants** — who may *invoke* which endpoint/agent/tool | MVP | Default deny; grants to groups/users/service accounts |
| F-64 | **Approval workflow** (four-eyes) for prod publishes and sensitive data | MVP | Author cannot approve own change |
| F-65 | **Service accounts & API keys** for machine consumers | MVP | Hashed, scoped, expiring, rotatable |
| F-66 | **Immutable audit log** + export (SIEM: JSON lines / OTLP logs) | MVP | Every admin action, publish, invoke, tool call, denial |
| F-67 | **Access review report** — who has what, last used | v1.x | Exportable CSV |
| F-68 | **Just-in-time elevation** (time-boxed admin) | v2 | Auto-expires, audited |
| F-69 | **SCIM 2.0 group sync** for hosts without group claims | v2 | |

### H. Operations (P2)
| ID | Feature | Tier | Acceptance criteria |
|----|---------|------|---------------------|
| F-70 | **Token & cost budgets** per workspace/agent/user with alerts & hard caps | MVP | Hard cap → 429 with clear problem type |
| F-71 | **Usage dashboard** — calls, latency, tokens, cost, errors | MVP | Micrometer metrics + UI |
| F-72 | **Trace viewer** for agent turns (prompt, tool calls, results — redacted) | MVP | Linked by trace id |
| F-73 | **Kill switch** per agent/endpoint/workspace/global | MVP | Takes effect cluster-wide ≤ 10 s |
| F-74 | **Config export/import & GitOps mode** (YAML bundles, signed) | v1.x | Round-trip lossless |
| F-75 | **Environment promotion** dev→staging→prod | v1.x | Bundle signature verified on import |
| F-76 | **PII redaction** in prompts/logs/traces, **prompt validation** (malicious content, business scope against the catalog) and a **backend-controlled structured display** of answers | MVP | Configurable detectors and validators (SPIs); tests with seeded PII and attack prompts (LLD-06 §8) |
| F-78 | **Audit evidence mode** — opt-in, encrypted capture of prompts, outputs and returned rows for regulated workspaces | v1.x | Auditor-only access (audited); crypto-shredding per data subject; standard audit keeps only hashes |
| F-77 | **Data residency / provider allow-list** per workspace | v1.x | Classification RESTRICTED ⇒ only on-prem/approved models |

## 4. Explicit non-goals (v1)
- WebFlux hosts (see OQ-01).
- Arbitrary code execution / scripting by admins.
- Writes through the generic query engine or native SQL (writes only via reviewed proposals applied through host service methods / JPA, LLD-11).
- Our own data-versioning tables — host versioning/audit tables remain the system of record.
- Owning user identities or passwords.
- Training/fine-tuning models.
