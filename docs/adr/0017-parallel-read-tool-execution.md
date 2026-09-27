# ADR-0017: Execute independent read tool calls in parallel within a turn
- Status: Proposed · Date: 2026-09-28

## Context
Models often request several independent lookups in one response (profile, billing history, tickets). Executing them
serially adds their latencies; the model round trip is already the dominant cost.

## Decision
A custom Spring AI `ToolCallingManager` runs all `readOnly=true` calls of one model response concurrently on virtual
threads, capped by `tools.max-parallel-per-turn` (default 4) with per-call deadlines and isolated failures; results are
returned in call order. Proposal-creating calls run afterwards, sequentially. `StructuredTaskScope` is not used (preview in Java 25).

## Consequences
+ Turn latency ≈ slowest read instead of the sum. − Burstier DB load (bounded by the DB bulkhead); security/scoped context must be copied per task (tested).
