# ADR-0006: Config propagation via DB snapshot generations + polling (push as hint)
- Status: Proposed · Date: 2026-09-27

## Context
Host apps run N replicas; published config must converge on all nodes, atomically, without requiring new infrastructure.

## Options considered
1. Message bus only (Kafka/Redis) — extra infra, lost messages.
2. DB as source of truth, monotonic snapshot generation, periodic poll; optional push notifier to reduce latency.
3. Distributed cache (Hazelcast) — heavy for a library.

## Decision
Option 2; config store accessed via JDBC in our own schema.

## Consequences
+ Works with only the host DB; idempotent, monotonic, rollback = republish. − Poll latency (default 5 s); DB load tiny (one indexed max() query per node per interval).
