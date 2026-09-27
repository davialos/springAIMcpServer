# springAIMcpServerCommon — Project Instructions

Embeddable Spring Boot library (starter JAR) that lets a **host** Spring Boot application
expose metadata-driven dynamic REST endpoints, safe dynamic database queries, and Spring AI
agents that understand the host's own code (`@Ai*` semantic annotations + entity graph) — all configured at
runtime from an admin control plane and governed by the host's existing access management.

## Current phase: RESEARCH & LOW-LEVEL DESIGN ONLY

- **Do not write Java implementation code.** Deliverables are Markdown design docs.
- Interface/record *signatures* inside LLD docs are allowed as design sketches
  (mark them `// design sketch`). No method bodies, no `src/` tree, no `pom.xml` yet.
- Every design decision that is hard to reverse gets an ADR in `docs/adr/`.
- Unresolved items go to `docs/open-questions.md` — never silently assume.

## Baseline (verified 2026-09-27, see docs/research/spring-ai-2-notes.md)

| Item | Version |
|------|---------|
| Java | 25 LTS (Temurin 25.0.3 installed locally) |
| Spring Boot | 4.1.x (Spring Framework 7, Jackson 3) |
| Spring Security | 7.1.x |
| Spring AI | 2.0.x (ToolCallback / ToolCallingAdvisor; `FunctionCallback` is legacy 1.x naming) |
| MCP Java SDK | 2.0 (spec 2025-11-25, Streamable HTTP) |

## Namespace rules (collision isolation with host)

- Config properties: `dynamic.ai.agent.*`
- DB tables: `dai_*` (own schema `dynamic_ai` where the DB supports it)
- HTTP: control plane `/dynamic-ai/admin/**`, dynamic data plane `/dynamic-ai/api/**`, MCP `/dynamic-ai/mcp`
- Project name: **springAIMcpServerCommon**; artifactIds `spring-ai-mcp-server-common-*`; base package `<org-domain>.springaimcpservercommon` (org domain TBD, ADR-0010)
- Web Component tag prefix: `saimcp-`
- Metrics/tracing: `dynamic.ai.agent.*` meter names, span prefix `dai.`

## Doc map

- `docs/README.md` — index and reading order
- `docs/01-feature-catalog.md` — user-facing features (F-xx IDs)
- `docs/02-architecture-overview.md` — HLD, modules, key flows
- `docs/lld/*.md` — one LLD per component
- `docs/security/*.md` — access management + threat model
- `docs/adr/*.md` — decisions
- `docs/production-readiness.md` — go-live gates

## Project agents (`.claude/agents/`)

| Agent | Use for |
|-------|---------|
| `lld-chief-architect` | Cross-component design, module boundaries, ADRs, consistency |
| `metadata-extraction-designer` | `@Ai*` annotations, runtime scan, registry & policy resolution chain |
| `dynamic-runtime-designer` | Dynamic endpoints + dynamic query engine |
| `agent-runtime-designer` | Spring AI agents, tool bridge, memory, RAG, MCP |
| `access-management-architect` | AuthN/AuthZ integration with host IAM, RBAC/ABAC, audit |
| `control-plane-designer` | Admin API + dashboard UX, publish lifecycle |
| `production-readiness-reviewer` | Resilience, observability, ops, security review gate |

## Design conventions

- Every LLD follows the template in `docs/lld/_template.md`.
- Feature IDs `F-xx`, permission IDs `perm:<resource>:<action>`, ADR IDs `ADR-nnnn`.
- Apply the global rules: clean-architecture (ports/adapters), release-it (timeouts,
  bulkheads), ddia (one owner per fact), code-complete (validate at trust boundaries).
- **Writes:** never executed by a model. Only user-reviewed, explicitly confirmed change proposals,
  applied through host write paths so host versioning/audit/identity flows record them (ADR-0009, LLD-11).
- **Host safety (LLD-12):** fail the feature, not the host; no global Spring side effects; UNKNOWN tier = PROD;
  authoring/introspection/preview are off in PROD by default (ADR-0011); never shade Spring-managed libs (ADR-0012).
- Default deny: nothing in the host is reachable by an agent/endpoint unless explicitly
  allow-listed AND authorized for the calling principal.
