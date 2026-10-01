# Design Documentation — springAIMcpServerCommon

Status: **Research & LLD phase (no implementation yet)** · Started 2026-09-27

## Reading order

| # | Doc | Owner agent | Status |
|---|-----|-------------|--------|
| 0 | [research/spring-ai-2-notes.md](research/spring-ai-2-notes.md) — verified platform facts | lld-chief-architect | Draft v1 |
| 1 | [01-feature-catalog.md](01-feature-catalog.md) — personas & user features | control-plane-designer | Draft v1 |
| 2 | [02-architecture-overview.md](02-architecture-overview.md) — HLD, modules, flows | lld-chief-architect | Draft v1 |
| 3 | [lld/01-module-structure-and-autoconfiguration.md](lld/01-module-structure-and-autoconfiguration.md) | lld-chief-architect | Draft v1 |
| 4 | [lld/02-metadata-extraction.md](lld/02-metadata-extraction.md) — `@Ai*` annotations & runtime scan | metadata-extraction-designer | Draft v2 |
| 5 | [lld/03-metadata-registry.md](lld/03-metadata-registry.md) — registry & policy resolution chain | metadata-extraction-designer | Draft v2 |
| 6 | [lld/04-dynamic-endpoints.md](lld/04-dynamic-endpoints.md) | dynamic-runtime-designer | Draft v1 |
| 7 | [lld/05-dynamic-query-engine.md](lld/05-dynamic-query-engine.md) | dynamic-runtime-designer | Draft v1 |
| 8 | [lld/06-agent-runtime.md](lld/06-agent-runtime.md) | agent-runtime-designer | Draft v1 |
| 9 | [lld/07-tool-bridge-and-mcp.md](lld/07-tool-bridge-and-mcp.md) | agent-runtime-designer | Draft v1 |
| 10 | [lld/08-control-plane-and-dashboard.md](lld/08-control-plane-and-dashboard.md) | control-plane-designer | Draft v1 |
| 11 | [lld/09-persistence-and-config-lifecycle.md](lld/09-persistence-and-config-lifecycle.md) | control-plane-designer | Draft v1 |
| 11b | [lld/11-write-proposals-and-review-ui.md](lld/11-write-proposals-and-review-ui.md) — reviewed writes, UI components, host versioning/audit reuse | agent-runtime-designer | Draft v1 |
| 11c | [lld/12-host-safety-and-environment-containment.md](lld/12-host-safety-and-environment-containment.md) — environment tiers, prod lock-down, fault isolation, availability probes | lld-chief-architect | Draft v1 |
| 11d | [lld/13-streaming-response-protocol.md](lld/13-streaming-response-protocol.md) — SSE event contract, heartbeat, cancellation, client rendering | agent-runtime-designer | Draft v1 |
| 11e | [lld/14-performance-and-throughput.md](lld/14-performance-and-throughput.md) — concurrency, provider rate governor, pagination, parallel tools, budgets | lld-chief-architect | Draft v1 |
| 12 | [lld/10-observability-cost-quota.md](lld/10-observability-cost-quota.md) | production-readiness-reviewer | Draft v1 |
| 13 | [security/01-access-management.md](security/01-access-management.md) | access-management-architect | Draft v1 |
| 14 | [security/02-threat-model.md](security/02-threat-model.md) | access-management-architect | Draft v1 |
| 15 | [production-readiness.md](production-readiness.md) — go-live gates | production-readiness-reviewer | Draft v1 |
| 16 | [lld/15-database-schema.md](lld/15-database-schema.md) — PostgreSQL schema: tables, keys, indexes, partitions, retention | lld-chief-architect | Draft v1 |
| 16b | [lld/16-load-test-generator.md](lld/16-load-test-generator.md) — k6 load-test generator: API discovery, data providers, load modes (developer tool, ADR-0022) | lld-chief-architect | Implemented v1 |
| 17b | [integration/load-testing-guide.md](integration/load-testing-guide.md) — how to generate and run k6 load tests for a project | lld-chief-architect | v1 |
| 17 | [integration/host-integration-guide.md](integration/host-integration-guide.md) — how a host app adopts the starter | control-plane-designer | Draft v1 |
| 18 | [integration/annotation-best-practices.md](integration/annotation-best-practices.md) — `@Ai*` annotation best practices, centralized configuration, per-annotation scenarios | metadata-extraction-designer | Draft v1 |
| — | [../scripts/db/postgresql/](../scripts/db/postgresql/) — DBA scripts: roles, database, schema, grants, verification | — | v1 |
| — | [tools/jfr-analyzer.md](tools/jfr-analyzer.md) — JFR recording analyzer (developer tool) | — | v1 |
| — | [adr/](adr/) — architecture decisions | lld-chief-architect | — |
| — | [open-questions.md](open-questions.md) | all | Living |
| — | [research/design-note-07-starter-hygiene-evaluation.md](research/design-note-07-starter-hygiene-evaluation.md) — verdicts on the starter-hygiene note | lld-chief-architect | Record |

## Conventions
- LLDs follow [lld/_template.md](lld/_template.md).
- IDs: features `F-nn`, permissions `perm:<resource>:<action>`, ADRs `ADR-nnnn`, open questions `OQ-nn`.
- Code blocks marked `// design sketch` are contracts, not implementation.

## Workflow
1. Designer agent drafts/edits its LLD.
2. `production-readiness-reviewer` reviews → findings table.
3. `lld-chief-architect` resolves cross-doc conflicts, writes ADRs, updates this index.
4. A doc moves to **Approved** only when the reviewer verdict is `READY`.
