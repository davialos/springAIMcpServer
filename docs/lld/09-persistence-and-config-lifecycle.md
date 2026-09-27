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
| Table | Key columns | Notes |
|-------|-------------|-------|
| `dai_workspace` | id, slug (unique), name, classification_clearance, created_* | |
| `dai_workspace_member` | workspace_id, subject_type(USER/GROUP/SA), subject_id, role | subject = external IdP id/group, not our user |
| `dai_resource` | id, workspace_id, kind, slug, current_published_rev, status | kind ∈ ENDPOINT, QUERY, AGENT, TOOL, ROW_POLICY |
| `dai_resource_revision` | resource_id, rev, state, spec_json (jsonb/clob), spec_hash, catalog_hash, author, submitted_at, approved_by, approved_at | immutable after submit (trigger or app-enforced) |
| `dai_review` | revision id, reviewer, decision, comment, risk_score, at | |
| `dai_snapshot` | generation (PK, monotonic), published_by, published_at, manifest_json (resource→rev list), manifest_hash | append-only |
| `dai_node_state` | node_id, applied_generation, heartbeat_at, version | cluster status |
| `dai_grant` | id, workspace_id, resource_id/pattern, subject_type, subject_id, permission, conditions_json, expires_at | |
| `dai_role_mapping` | id, source (OIDC_CLAIM/AUTHORITY/LDAP_GROUP), match_expr, framework_role, workspace_id? | |
| `dai_service_account` / `dai_api_key` | key_id, prefix, hash (argon2id/bcrypt), scopes, expires_at, last_used_at | |
| `dai_budget`, `dai_usage_ledger` | scope, period, limit_tokens/cost; ledger rows per invocation (aggregated hourly) | |
| `dai_kill_switch` | scope, target, enabled, reason, set_by, at | |
| `dai_conversation`, `dai_conversation_message`, `dai_change_proposal*` (LLD-11), `dai_agent_trace` | | retention jobs |
| `dai_audit_event` | id (ULID), at, actor, action, resource, decision, reason, trace_id, details_json, prev_hash, hash | hash-chained, append-only |
| `dai_schema_history` | Flyway | own history table |

Portability: PostgreSQL (primary), MySQL 8, Oracle 19+, SQL Server 2022, H2 (tests). JSON
stored as `jsonb` on PG, `CLOB`/`JSON` elsewhere via per-vendor Flyway locations.
Access via **JDBC (`JdbcClient`)**, not JPA — keeps our tables out of the host's
persistence unit (OQ-03 leaning).

## 4. Propagation (ADR-0006)
- Publish = one transaction: insert `dai_snapshot(g+1)` with full manifest, update resource pointers, audit.
- Each node runs `SnapshotWatcher`: polls `SELECT max(generation)` every `poll-interval` (default 5 s,
  jittered); if newer → load manifest + revisions → build immutable `PublishedSnapshot` →
  validate → swap → update `dai_node_state`.
- Optional push: `SnapshotChangeNotifier` SPI (Postgres `LISTEN/NOTIFY`, Redis pub/sub,
  Kafka, Spring Cloud Bus) triggers an immediate poll — push is a **hint**, polling is the guarantee.
- Kill switches polled on a faster interval (default 2 s) and cached separately (F-73 ≤ 10 s).
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
