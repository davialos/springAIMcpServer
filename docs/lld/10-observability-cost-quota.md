# LLD-10: Observability, Cost & Quota

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | production-readiness-reviewer (with agent-runtime-designer) |
| Module(s) | `core` (ports), autoconfigure (Micrometer wiring) |
| Related features | F-24, F-66, F-70, F-71, F-72, F-76 |

## 1. Purpose
Make every invocation measurable, attributable (to workspace/agent/principal), and
bounded in cost; integrate with the host's existing Micrometer/OpenTelemetry stack — never ship our own backend.

## 2. Metrics (Micrometer, prefix `dynamic.ai.agent`)
| Meter | Type | Tags |
|-------|------|------|
| `.endpoint.requests` | Timer | workspace, endpoint, status_class, outcome |
| `.query.executions` | Timer | workspace, query, outcome |
| `.agent.turns` | Timer | workspace, agent, model, outcome |
| `.agent.tool.calls` | Timer | agent, tool, outcome (ok/denied/error/timeout/confirmation) |
| `.llm.tokens` | Counter | workspace, agent, model, type (input/output/cached) |
| `.llm.cost` | Counter (currency micro-units) | workspace, agent, model |
| `.budget.utilization` | Gauge | scope, period |
| `.ratelimit.rejections` | Counter | scope |
| `.snapshot.generation` / `.snapshot.lag` | Gauge | node |
| `.authz.decisions` | Counter | plane, decision |
Cardinality guard: never tag by principal, conversation, or raw path; tag values limited to IDs of published resources.
Spring AI's own `gen_ai.client.*` observations are kept and correlated.
Multi-tenant hosts: a `tenant` tag is **off by default** and only allowed with a bounded tenant count
(`metrics.tenant-tag.max-values`, default 100; overflow collapses to `other`) — a per-tenant tag on
token counters is a classic time-series cardinality explosion. Per-tenant cost detail lives in the usage ledger, not in meters.
The library never starts its own metrics server or exporter; it only registers meters in the host's `MeterRegistry`.

## 3. Tracing
Observation API → OTel. Spans: `dai.endpoint`, `dai.query`, `dai.agent.turn`, `dai.tool`,
`dai.snapshot.apply`, plus Spring AI chat/tool spans. Content recording off by default;
when on, passes through the redaction pipeline.

**Implemented (2026-09-29, extended 2026-09-30):** six spans, each also a meter (Micrometer `Observation`, so a host with a `MeterRegistry`
gets timers and one with Micrometer Tracing gets spans; without either they cost nothing). The meter names follow the
`dynamic.ai.agent.*` convention, the span names the `dai.*` one:

| Span (`contextualName`) | Meter | Tags (low cardinality) | Attributes (high cardinality, spans only) |
|---|---|---|---|
| `dai.agent.turn` | `dynamic.ai.agent.turn` | `dai.agent.slug`, `dai.channel`, `dai.streaming`, `dai.turn.outcome`, `dai.turn.finish`, `dai.turn.error_code` | `dai.turn.id`, `dai.model_call.id`, `dai.agent.revision`, `dai.tokens.input/output` |
| `dai.tool` | `dynamic.ai.agent.tool` | `dai.tool.name`, `dai.tool.access_mode`, `dai.channel`, `dai.tool.status`, `dai.tool.error_code`, `dai.tool.write_violation` | `dai.turn.id`, `dai.model_call.id`, `dai.mcp.request.id` |
| `dai.endpoint` | `dynamic.ai.agent.endpoint` | `dai.endpoint.method`, `dai.endpoint.route`, `dai.endpoint.status` (`2xx`..`5xx`) | `dai.endpoint.id`, `dai.workspace.id` |
| `dai.query` | `dynamic.ai.agent.query` | `dai.query.outcome` (`ok`, `truncated`, `error`) | `dai.query.id`, `dai.workspace.id`, `dai.query.rows` (a count, never a row) |
| `dai.snapshot.apply` | `dynamic.ai.agent.snapshot.apply` | `dai.snapshot.cache` (`agents`, `queries`, `tool-bindings`), `dai.snapshot.outcome` (`applied`, `missing`, `failed`) | `dai.snapshot.generation` |
| `dai.mcp` | `dynamic.ai.agent.mcp` | `dai.mcp.method` (known methods only, else `other`), `dai.mcp.status`, `dai.mcp.error_code` | `dai.mcp.request.id`, `dai.mcp.tool`, `dai.workspace.id` |

The ids are the primary keys of `dai_agent_turn`, `dai_model_call`, `dai_mcp_request`, so a trace can be followed to the
store rows and, through `GET …/traces/by-trace-id/{traceId}`, back. The `traceId` recorded on turn and MCP request rows
is read while the span is open, so it is that span's trace. Never attached: prompts, answers, tool arguments or results
(content recording stays off, see above). A failed turn marks its span as an error. The tool span's parent is the turn
span, set explicitly because a streamed turn may run its tools on another thread; a **synchronous** turn also keeps its
span current, so Spring AI's own spans and the host's below it nest under it. A **streamed** turn does not (no reliable
thread propagation), so Spring AI's chat spans of a streamed turn are siblings, not children (OQ-50). The library
registers no `ObservationRegistry` of its own: it uses the host's when there is one and is silent otherwise.

Every span joins the host's current trace (Micrometer Tracing with the host's OTel or Brave bridge), so one trace
shows the split between LLM call, each tool call, and each dynamic query. The LLM segment is measured by the
**client-side** span around the provider call; that needs no cooperation from the provider.
Propagating `traceparent` **headers to external LLM providers** is a separate choice: off by default
(`observability.propagate-to-providers=false`) because it sends internal trace identifiers to a third party and
adds nothing to our own timing; on by default for self-hosted/on-prem model endpoints and outbound MCP servers inside the company network.

## 4. Audit (distinct from logs)
`AuditSink` port; default JDBC hash-chained table + optional forwarders (JSON-lines log
appender with `DAI_AUDIT` marker, OTLP logs). Event catalog:
`ADMIN_CHANGE, REVISION_SUBMITTED/APPROVED/REJECTED/PUBLISHED/ROLLED_BACK, GRANT_CHANGED,
ROLE_MAPPING_CHANGED, APIKEY_CREATED/REVOKED, ENDPOINT_INVOKED, AGENT_TURN, TOOL_INVOKED,
TOOL_DENIED, AUTHZ_DENIED, BUDGET_EXCEEDED, KILL_SWITCH_CHANGED, DATA_EXPORTED`.
Invocation events can be sampled (config), security events never.

### 4.1 Audit tiers (data minimisation vs evidence)
Recording "the exact prompt, the rows returned and the final prompt" for every call in a log file would copy
personal/health/financial data into log pipelines that usually lack the access controls, retention and erasure
support the source systems have (GDPR data minimisation and storage limitation; HIPAA minimum-necessary).
So audit is tiered:
| Tier | Default | Captures | Where |
|------|---------|----------|-------|
| **Standard** | always on | who (subject, roles), when, agent/tool/endpoint + revision, decision & reason, applied filters (non-sensitive), **row count + entity IDs + SHA-256 of the result**, token usage, model, trace id, proposal ↔ host revision | hash-chained `dai_audit_event`; JSON-lines forwarder (`DAI_AUDIT` marker) |
| **Evidence mode** | opt-in per workspace/agent (ADR-0018) | the above **plus** full prompt sent to the model (post-redaction), model output, tool arguments and returned rows | separate `dai_audit_evidence` store, **envelope-encrypted** with a key from the host's KMS/vault (`EvidenceKeyProvider` SPI); readable only by `AUDITOR` with a reason (the read itself is audited); own retention (default 400 days) and legal hold |
The hashes in the standard tier let an auditor prove *which* data was returned (by recomputing against the
source or the evidence store) without the standard log containing that data.
Erasure: evidence is encrypted per data subject where a subject is known (crypto-shredding: deleting that key
erases the subject's evidence while the hash chain stays valid). Optional export of the chain head to WORM storage
(e.g. S3 Object Lock) for tamper-evidence beyond the database.

## 5. Cost model
`PriceTable` (per provider/model, input/output/cached per 1M tokens, currency) —
admin-configured, versioned. Cost = usage metadata from `ChatResponse` × price.
Ledger writes are asynchronous & batched (bounded queue; on overflow → aggregate in memory,
never block the request; drop counter metric).

## 6. Budgets & quotas (F-70)
Scopes: global, workspace, agent, principal. Periods: day, month. Soft limit → alert event;
hard limit → reject pre-turn. Cluster accuracy: reservation model — pre-turn reserve
estimated tokens via the same `RateLimiterBackend` port endpoints use (LLD-04 §4, ADR-0021), settle after
turn. Default backend is PostgreSQL (no new infrastructure); a host at scale may supply a Redis/Hazelcast
implementation of the port instead. Rate limits (requests/min) share the same backend.

**Implemented (v1 slice):** the invocation path checks budgets *before* each turn, without a reservation.
`LedgerBudgetChecker` loads the enabled budgets that apply to (workspace, agent, principal), compares the
current-period usage of the hourly ledger with each limit and refuses the turn when a hard limit is
reached: `429 budget-exhausted` from `AgentChatController` (before a stream opens) and from
`DefaultAgentInvoker` (other channels; a stream reports a terminal non-retryable `budget-exhausted` error
event). Soft limits and non-hard limits only raise `BUDGET_SOFT_LIMIT_REACHED` / `BUDGET_EXCEEDED` audit
events (once per budget and period per node) and the counter `dynamic.ai.agent.budget.events`; refused turns
count in `dynamic.ai.agent.budget.blocked`. `LedgerUsageSink` writes tokens and priced cost of every completed
model call (sync and streamed) into the ledger. Accuracy: usage is recorded after the call, so concurrent turns
can overshoot by the tokens in flight, plus up to the decision cache TTL (OQ-40). If the store cannot be read
the check fails open by default (logged and counted in `dynamic.ai.agent.budget.check.errors`).

| Property | Default |
|----------|---------|
| `dynamic.ai.agent.budget.enforce` | `true` (`false` = record and show usage, refuse nothing) |
| `dynamic.ai.agent.budget.cache-ttl` | `10s` (`0` disables the per-(workspace, agent, principal) decision cache) |
| `dynamic.ai.agent.budget.fail-open` | `true` |

**Turn telemetry (implemented slice):** `DefaultAgentInvoker` reports every finished turn, including refused, failed and cancelled ones, to the `TurnRecorder` port (no-op by default). `StoreTurnRecorder` writes one `dai_agent_turn` row and, when the provider reported tokens, one priced `dai_model_call` row on a virtual thread behind a 64-slot bulkhead; a full bulkhead or a failed write drops the record and increments `dynamic.ai.agent.turn.record.dropped` / `...failures`, never affecting the turn. Records hold identifiers, timings, token counts and outcome codes only. The turn id is the one the client saw (SSE ids, replay URL, response). See OQ-43 for what is not recorded yet.

## 7. Health & readiness
`HealthContributor` `dynamicAi` with details: catalog state, snapshot generation & lag,
config store reachability, model providers' breaker states. Configurable whether it
joins the host's readiness group (default: no — we must not take the host down).

## 8. Alerts (shipped as example Prometheus rules / Grafana dashboard JSON)
Snapshot lag > 60 s; agent error rate > 5 % 5m; model breaker open; budget ≥ 80 %; authz
denial spike; audit write failures > 0; query p99 > timeout × 0.8.
