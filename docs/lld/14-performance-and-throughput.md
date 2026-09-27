# LLD-14: Performance & Throughput

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | lld-chief-architect (with production-readiness-reviewer) |
| Module(s) | `core` (budgets, admission), `ai` (tool execution, prompt layout), `jpa` (pagination), `webmvc` |
| Related features | F-17, F-43, F-54, F-70 |
| Related ADRs | ADR-0007, ADR-0015, ADR-0017 |
| Input | Product-owner design note "High throughput & low latency" (2026-09-28) — evaluated in §9 |

## 1. Where the time and capacity actually go
| Stage | Typical share of an agent turn | Lever |
|-------|-------------------------------|-------|
| LLM generation (time-to-first-token + tokens/s) | 80–95 % | Fewer model round trips (outcome tools, parallel tool calls), shorter prompts, provider prompt caching, smaller models where good enough |
| Tool execution (DB / host services) | 5–15 % | Query pushdown, pagination, parallel execution, indexes |
| Framework overhead (authz, schema validation, masking, audit) | < 1 % (target p99 < 5 ms) | Immutable snapshots, cached compiled plans, async audit |
The **capacity** ceiling is usually the provider's rate limits (requests/min and tokens/min per API
key and model), then the DB connection pool, and only then threads. The design manages all three explicitly.

## 2. Concurrency (JVM, Java 25)
- **Host threads:** we *recommend* `spring.threads.virtual.enabled=true` in the integration guide, but the library
  never sets host properties. Everything works with platform threads too.
- **Our I/O work** (LLM calls, tool calls, MCP client calls, probes) runs on a library-owned
  `Executors.newVirtualThreadPerTaskExecutor()` — no fixed pools for I/O (ADR-0007).
- **Virtual threads remove thread scarcity, not downstream limits.** Each downstream sits behind a semaphore bulkhead:
  | Bulkhead | Default permits | Sized from |
  |----------|-----------------|-----------|
  | `llm.<provider>` | 64 per node | provider RPM/TPM ÷ nodes (§4) |
  | `db.dynamic-queries` | pool size × 0.25 | host Hikari `maximumPoolSize` (LLD-12 §4) |
  | `mcp-client.<server>` | 16 | remote server limits |
  Waiting for a permit has a deadline (`bulkhead.max-wait`, default 2 s) → 503 `overloaded` rather than an unbounded queue.
- **Pinning on Java 25:** since JDK 24 (JEP 491) `synchronized` no longer pins virtual threads, so the old
  "avoid synchronized" advice is mostly obsolete for our Java 25 baseline. Remaining pinning sources are native
  frames (JNI/FFM upcalls) and class initialisation. We detect pinning with the JFR event `jdk.VirtualThreadPinned`
  in load tests (a failing gate if our code appears in pinned stacks).
- **CPU-bound work** (JSON-schema validation of large payloads, redaction regexes, token counting) doesn't pin,
  but it occupies a carrier thread without time-slicing. It is kept small and bounded (payload caps) rather than moved to a separate pool;
  if profiling shows otherwise, a small platform `ForkJoinPool` is the escape hatch.
- **Context propagation:** `ScopedValue` for our invocation context; Spring `SecurityContext` copied explicitly into
  each task; Micrometer context-propagation for trace IDs.

## 3. Tool-call design (fewer round trips, smaller payloads)
### 3.1 Outcome-oriented actions (guidance + lint for host developers)
Every extra tool the model must chain costs a full LLM round trip (typically seconds). Host developers should expose
**business outcomes** (`@AiExposedAction` on `RefundService.previewRefund(orderId, reason)`) rather than granular steps
(`get_order`, `calculate_discount`, `apply_refund`), orchestrating server-side in their own service.
- In this framework an outcome that **writes** is still proposal-only (LLD-11): `process_refund` returns a proposal whose
  review payload shows the computed discount and refund. One model round trip, one human confirmation.
- Scan-time lint (LLD-02): > 8 actions on the same entity, or chains the evals repeatedly observe
  (A→B→C in most trajectories), produce a catalog hint "consider an outcome action".
- Large tool sets: `ToolSearchToolCallingAdvisor` above `tool-search-threshold` (LLD-06 §3).

### 3.2 Argument shape
- Prefer flat parameters: primitives, `String`, enums, `java.time` types; nesting depth ≤ 2; arrays bounded (`maxItems`).
- Scan issue `COMPLEX_TOOL_ARGS` (warning) for deeper structures, maps, or polymorphic types. Main benefit: fewer malformed
  calls and retries; the serialization cost itself is negligible next to model latency.
- Enums surface as JSON-schema `enum` so the model can't invent values.

### 3.3 Mandatory pagination (F-17)
- Every list-returning tool and query is paginated. The result envelope (LLD-07 §3a) gains
  `page: { "limit": 50, "hasMore": true, "nextCursor": "…" }`.
- **Cursors:** keyset pagination preferred (stable under concurrent inserts, no deep-offset scans), offset allowed for
  small tables. The cursor is **opaque and HMAC-signed**, bound to (query/action, normalized filters, principal, snapshot
  generation) — a model cannot forge or replay one to widen a query or reach another user's data. Expired cursor → `status: error, code: cursor_expired`.
- Host actions returning `List`/`Collection`: pagination can't be added *inside* a host method, and truncating afterwards
  doesn't prevent the heap allocation. So the scan requires one of: a Spring Data `Pageable`/`Limit` parameter, a return type of
  `Page`/`Slice`/`Window` (Spring Data's keyset `Window` maps directly to `nextCursor`), or an `@AiParam`-annotated `limit` parameter.
  Otherwise → issue `UNBOUNDED_LIST_ACTION`; excluded when `scan.strict=true`.
- Dynamic queries (LLD-05) already cap rows; they now return `hasMore` by fetching `limit + 1`.

### 3.4 Query pushdown
Already the design (ADR-0004): filters, sorting, and limits compile to Criteria predicates executed in the database; the
JVM never filters result sets and the model is never handed raw rows to filter. For host actions, guidance: accept filter
parameters and push them into repository queries.

## 4. LLM throughput & latency
- **Provider rate governor:** token-bucket limiter per (provider, model, API key) configured from the provider's published
  quotas (RPM, input TPM, output TPM), shared across nodes via the same backend as budgets (LLD-10 §6). Requests that would exceed
  the bucket wait up to `llm.max-queue-wait` (default 5 s) in a bounded, **fair per-workspace** queue, then fail with 429 `provider_capacity`.
  This stops one team's burst from causing provider 429 storms for everyone.
- **Retries:** only on provider 429/5xx and connection errors, with exponential backoff + jitter, honouring `Retry-After`,
  max 2 attempts, and never once tokens have been streamed to the client.
- **Prompt caching:** layout is deterministic for provider-side prompt caching (Anthropic/OpenAI/Bedrock): system prompt →
  tool definitions (**stable sorted order**) → conversation. Per-request variable data (principal, date) goes after the
  cached prefix. Cached tokens are metered separately (LLD-10 `type=cached`).
- **Connection reuse:** the model clients are Spring AI's provider SDK clients (2.0 uses the official OpenAI/Anthropic SDKs),
  created once per provider configuration and reused — keep-alive pools, never a client per request. HTTP/2 is used where the SDK
  and provider support it; we don't wrap or replace the SDK's HTTP stack.
- **Model tiering:** agents may set a cheaper/faster `routerModel` for tool selection and a stronger model for the final answer (v1.x).

## 5. Parallel tool execution within a turn (F-54, ADR-0017)
When one model response contains several tool calls:
- Calls whose actions are all `readOnly=true` run **concurrently** on virtual threads, bounded by
  `tools.max-parallel-per-turn` (default 4) and each call's timeout; results are returned to the model in the original call order.
- Each task gets its own copy of the security context and `ScopedValue` invocation context; per-turn call limits are
  counted before dispatch (a response with 12 calls against a limit of 10 executes none beyond the limit).
- Failures are isolated: one failing or timing-out call yields its own `status: error|unavailable` envelope; the others complete.
- Proposal-creating (write) calls run sequentially after the reads, so a proposal's before-snapshot reflects a consistent read.
- Implemented as our own `ToolCallingManager` (Spring AI SPI) using an `ExecutorService` with deadlines. Java 25's
  `StructuredTaskScope` is still a preview API, so it is not used in the library.

## 6. Warm-up & caching
| What | When | Notes |
|------|------|-------|
| Effective catalog, compiled query plans, JSON schemas | Startup and each snapshot swap | Immutable; never on the request path |
| `ChatClient` per agent revision, tool callbacks per binding | Startup + snapshot swap | Per-request work = grant filtering only |
| Hibernate query-plan cache for published queries | Warm-up compiles each published query once (no execution) | |
| Provider TLS/connection | Optional `warmup.providers=true`: a zero-cost call (e.g. list models) at startup | Off by default: some providers bill or rate-limit it |
| Readiness | Our health contributor reports UP only after warm-up | Host readiness unaffected unless opted in (LLD-10 §7) |
| **Tool result cache** (opt-in per binding) | `readOnly` + `idempotent` actions only | Key = (action, normalized args, principal policy fingerprint); TTL ≤ 60 s; Caffeine, bounded; never shared across principals with different row policies |
| Embedding models (RAG, v1.x) | Loaded once at startup when a local model is configured | Remote embedding APIs use the same governor as §4 |

## 7. Memory & GC
- All buffers bounded (LLD-12 §4, LLD-13 §4). Streamed payloads are small text deltas; large tool results are truncated and paginated, never streamed wholesale.
- GC recommendation for hosts running heavy AI traffic: generational ZGC (`-XX:+UseZGC`, generational by default since JDK 23)
  for sub-millisecond pauses. A host-level choice, documented in the integration guide.

## 8. Performance budgets & verification
| Metric | Budget | Measured by |
|--------|--------|-------------|
| Framework overhead per endpoint call (excl. DB/LLM) | p99 < 5 ms | JMH + load test with stubbed backends |
| Tool dispatch overhead (authz + validation + masking + audit enqueue) | p99 < 3 ms | same |
| Time-to-first-token overhead added by us | p99 < 50 ms | stream tests with a fake model |
| Parallel tool fan-out (4 reads of 200 ms each) | ≈ 200–250 ms, not 800 ms | integration test |
| Sustained agent turns per node (fake model, 2 s latency) | ≥ 1 000 concurrent streams without errors | Gatling/k6 |
| Pinned virtual threads in our code | 0 | JFR `jdk.VirtualThreadPinned` |
| Provider 429s under a burst of 5× quota | 0 reaching users as unhandled errors (queued or clean 429 `provider_capacity`) | chaos test with a quota-emulating stub |

## 9. Evaluation of the input design note
| Proposal | Verdict | Reason / change |
|----------|---------|-----------------|
| `spring.threads.virtual.enabled=true` | **Adopted as a host recommendation** | A library must not set host properties; works either way |
| `newVirtualThreadPerTaskExecutor` instead of fixed pools | **Adopted, with bulkheads** | Unbounded concurrency just moves the overload to the DB pool and provider quotas (§2) |
| Beware pinning from `synchronized` | **Updated for Java 25** | JEP 491 (JDK 24) removed `synchronized` pinning; remaining sources are native frames/class init; verified with JFR (§2) |
| Flatten tool arguments | **Adopted** | As scan lint and guidance; the win is call accuracy, not serialization speed (§3.2) |
| Outcomes over operations | **Adopted** | Guidance + lint; write outcomes remain proposal-only (§3.1) |
| Mandatory pagination with `has_more` | **Adopted, hardened** | Signed opaque cursors; unbounded host list actions flagged/excluded (§3.3) |
| Cache metadata graphs, keep LLM connections alive, warm-up | **Adopted** (mostly already designed) | Plus provider prompt caching, which saves more latency than any JVM-side cache (§4, §6). The note's 2 500 ms → 0.01 ms figures are not design inputs |
| Scheduled warm-up before traffic peaks | **Not needed** | Caches are rebuilt on snapshot change, not evicted by time |
| Parallel tool calls via `CompletableFuture.allOf` on virtual threads | **Adopted, bounded** | Reads only, per-turn parallelism cap, per-call deadlines, isolated failures, writes after reads (§5, ADR-0017) |
| Query pushdown | **Already designed** (ADR-0004) | — |
| HTTP/2 on `WebClient`/`RestClient` to the LLM | **Adapted** | Spring AI 2.0 providers use official SDK clients; we reuse them and enable HTTP/2 where supported instead of building our own client (§4) |
| Netty `ByteBuf` off-heap buffers | **Rejected** | Would require WebFlux/Netty in servlet hosts (ADR-0015, OQ-01); payloads are small and bounded; generational ZGC addresses pause times (§7) |
| (Missing from note) provider rate limits | **Added** | The real throughput ceiling; per-provider governor with a fair queue (§4) |

## 10. Open questions
OQ-25 (default `tools.max-parallel-per-turn`), OQ-26 (should `UNBOUNDED_LIST_ACTION` exclude by default, or only warn?).
