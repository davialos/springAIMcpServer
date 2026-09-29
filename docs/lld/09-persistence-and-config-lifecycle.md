# LLD-09: Persistence & Configuration Lifecycle

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | control-plane-designer |
| Module(s) | `core` (state machine, ports), `jpa` (JDBC store, migrations) |
| Related features | F-64, F-66, F-73, F-74, F-75 |
| Related ADRs | ADR-0006 |

## 1. Purpose & responsibilities
Durable storage of all framework configuration, its versioning & publish lifecycle, and
cluster-wide propagation of the published state. Source of truth for "what is live".

## 2. Revision lifecycle
```
            submit              approve               publish
  DRAFT ───────────► IN_REVIEW ─────────► APPROVED ──────────► PUBLISHED ──► SUPERSEDED (by newer publish)
    ▲  edit            │ reject               │ (auto if policy says                │ deprecate
    └──────────────────┘                      │  approval not required)            ▼
                                              └─ expire (30d) ──► STALE        DEPRECATED ─► RETIRED
  rollback = publish of a previous PUBLISHED/SUPERSEDED revision (new snapshot generation, audited)
```
Rules: revisions immutable after submit; approver ≠ author (enforced); publish re-validates
against current catalog & collisions; only one PUBLISHED revision per resource per environment.

## 3. Schema (`dai_*`, in schema `dynamic_ai`)
Superseded by **LLD-15** (`docs/lld/15-database-schema.md`), which is the single owner of the schema description, and
by the Flyway migrations in `spring-ai-mcp-server-common-persistence/src/main/resources/db/dynamic-ai/migration/`.
Decisions that changed since this draft:
- **PostgreSQL 15+ only** (OQ-10 resolved). No multi-vendor migrations.
- Access through **JPA entities in an isolated persistence unit** (own EMF, transaction manager and Flyway, none
  registered as beans) — ADR-0019 supersedes the earlier JDBC-only leaning (OQ-03 resolved).
- Resource kinds are `ENDPOINT, QUERY, AGENT, TOOL_BINDING, ROW_POLICY, POLICY_OVERLAY, MCP_SERVER`; the published
  revision is not a column on `dai_resource` but the revision in state `PUBLISHED` (partial unique index).
- The snapshot manifest is normalised into `dai_snapshot_entry` rows instead of a JSON column.
- The usage ledger is `dai_usage_hourly` (derived, rebuildable hourly aggregate) instead of per-invocation ledger rows;
  per-invocation facts live in `dai_model_call`.
- Agent traces are split into `dai_agent_turn`, `dai_model_call`, `dai_tool_invocation` (monthly partitions).
- API key secrets use `hmac-sha256` with a server pepper by default.

## 4. Propagation (ADR-0006)
- Publish = one transaction: insert `dai_snapshot(g+1)` with full manifest, update resource pointers, audit.
- Each node runs `SnapshotWatcher`: polls `SELECT max(generation)` every `poll-interval` (default 5 s,
  jittered); if newer → load manifest + revisions → build immutable `PublishedSnapshot` →
  validate → swap → update `dai_node_state`.
- Optional push: `SnapshotChangeNotifier` SPI (Postgres `LISTEN/NOTIFY`, Redis pub/sub,
  Kafka, Spring Cloud Bus) triggers an immediate poll — push is a **hint**, polling is the guarantee.
- Kill switches polled on a faster interval (default 2 s) and cached separately (F-73 ≤ 10 s). **Implemented:** `StoreSecurityPorts.KillSwitches` reloads the active set every 2 s and is used by the authorization engine, the dynamic-endpoint check and the agent runtime; the grant, role-mapping and resource-status ports use 5 to 10 s node-local caches. The snapshot watcher and `dai_node_state` heartbeat are not implemented yet (OQ-46).
- Idempotent apply: applying generation g twice is a no-op; out-of-order hints ignored (monotonic).

## 5. Export / import & GitOps (F-74, F-75)
Bundle = YAML files per resource + `manifest.yaml` + detached signature (Ed25519; key via `SecretResolver`).
Import modes: `dry-run` (diff report), `apply-as-drafts`, `apply-and-publish` (requires approval policy satisfied or signed-by-trusted-key).
GitOps mode (`dynamic.ai.agent.config.mode=gitops`): UI becomes read-only for published
state; drafts come from repo via CI calling the import API.

## 6. Failure modes
| Failure | Behavior |
|---------|----------|
| DB unavailable at runtime | Keep last-good snapshot in memory; optional local file cache (`snapshot-cache-dir`) for cold starts |
| Partial manifest load | Discard whole generation; retry with backoff; alert after N failures |
| Clock skew | Generation is DB-sequence based, not time based |

## 7. Retention
Audit: default 400 days (configurable, export before purge). Traces: 30 days. Conversations: per agent (default 30 days). Usage ledger: 13 months aggregated.

## 8. Test strategy
Testcontainers matrix for all vendors; multi-node propagation test (two app contexts, one DB);
chaos: DB down during publish/apply; migration upgrade tests from every released schema version.
