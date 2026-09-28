# ADR-0021: Scalability defaults — stateless-first, horizontal scale-out, no forced new infrastructure
- Status: Accepted (2026-09-29) · Resolves OQ-07, OQ-22

## Context
The user asked for "best recommendations for a scalable system" as a standing bias for the rest of the
build. Two open questions were genuinely scalability decisions rather than product-preference questions:
whether the MCP server defaults to a stateful or stateless transport mode (OQ-22), and what backs the
shared rate limiter and budget counters across replicas (OQ-07). Both needed a real decision, not a "leave
it open" placeholder, before wave 3 builds the web layer and MCP server on top of them.

## Decision — general principle
For a library embedded in someone else's Spring Boot application, "scalable" means: **the host can run N
replicas behind a load balancer with no sticky-session requirement and no new infrastructure dependency
beyond what we already mandate (PostgreSQL)**, and any component that genuinely benefits from a faster
shared store (Redis, Hazelcast, …) is reachable through a port so a host operating at real scale can supply
one — never a hard dependency we impose on every host. Concretely, for the rest of this build:
- **Default to stateless request handling.** State that must be shared across replicas lives in PostgreSQL
  (already the single source of truth for config, audit, and telemetry — LLD-15) or is recomputed per
  request from an immutable in-memory snapshot (the effective catalog, compiled query plans — LLD-03),
  never in server-local session state unless a capability genuinely cannot work otherwise (see MCP below).
- **Bulkheads and caps over unbounded pools**, per node, so scaling out is "add a node" rather than "retune
  a shared limit" (already the LLD-12/LLD-14 design — this ADR does not change that, it reaffirms it).
- **Pluggable backend for anything that needs a fast shared counter**, with a default that reuses
  PostgreSQL (zero new infrastructure) and an SPI a host can implement against Redis or similar when its
  actual throughput needs it.
- Every new port introduced by this ADR follows the same `@ConditionalOnMissingBean` override rule as the
  rest of the framework (LLD-01 §3).

## Decision — OQ-22: MCP transport mode
**Stateless is the recommended default** (`dynamic.ai.agent.mcp.server.mode=stateless`), reversing the
LLD-07 §5.1 draft's "stateful default." Rationale: stateless mode needs no sticky sessions and no shared
session store, so any replica serves any request — the textbook horizontal-scaling shape, and the one that
works out of the box on a plain round-robin load balancer or a serverless/autoscaled deployment. The cost is
losing server→client `tools/list_changed` push notifications; clients re-list tools instead, which is a
minor UX/latency detail, not a correctness or scale problem. Stateful Streamable HTTP remains fully
supported and is the right choice for a host that (a) wants live tool-list updates and (b) already runs
sticky sessions or a shared session store for other reasons — but it is no longer what a host gets by not
choosing.

## Decision — OQ-07: rate-limit and budget counter backend
**Two-tier, port-based design**, not a single hardcoded store:
1. **Default:** PostgreSQL-backed (Bucket4j's JDBC integration, or an equivalent hand-rolled
   `SELECT … FOR UPDATE` / atomic `UPDATE … RETURNING` counter over `dai_budget`/a new rate-limit table) —
   zero new infrastructure, since every host already runs the `dynamic_ai` PostgreSQL store (ADR-0019).
   Correct and adequate for the overwhelming majority of embedding hosts, whose bottleneck is the LLM
   provider's own rate limit (LLD-14 §4) long before a few hundred permit-checks per second against
   PostgreSQL becomes the constraint.
2. **Escape hatch:** a `RateLimiterBackend` port (`tryAcquire(key, permits, window) -> Decision`,
   `currentUsage(key, window) -> long`) that autoconfigure wires to the PostgreSQL default via
   `@ConditionalOnMissingBean`. A host running at a scale where row-level contention on the rate-limit table
   actually matters supplies its own `RateLimiterBackend` bean (Redis via Bucket4j's Redis integration,
   Hazelcast, etc.) — the framework never requires that dependency itself.
3. The same port serves both per-endpoint/per-tool rate limits (LLD-04) and budget enforcement (LLD-10 §6);
   one mechanism, two call sites.

## Consequences
+ No host is forced onto Redis or any cache cluster just to run this library; a host at real scale isn't
  stuck with a PostgreSQL bottleneck either, because the port is there from day one, not bolted on later.
+ MCP works correctly behind a plain load balancer with zero session affinity configuration.
− Losing `tools/list_changed` by default means MCP clients must poll/re-list rather than being pushed
  updates; documented as a known trade-off, not a limitation to work around silently.
− The PostgreSQL-backed rate limiter adds write load to the store proportional to request volume; LLD-15's
  sizing guidance should account for this (follow-up: size the rate-limit table's row count and index for
  the "hot key, high-frequency update" access pattern, not the write-once telemetry pattern the rest of the
  schema is tuned for).
