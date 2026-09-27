# ADR-0011: Environment containment — capability matrix, fail-closed tier detection, break-glass override
- Status: Proposed · Date: 2026-09-27

## Context
Introspection, authoring, query preview, and playgrounds expose schema and live data. Hosts often leak
stage settings into prod (shared config, wrong profile). The data plane, however, must run in prod.

## Options considered
1. Profile-only `@Profile("!prod")` / `Condition` on the whole library — too coarse (kills the product in prod) and trusts profiles to unlock.
2. **Composite tier resolution (explicit property + profile heuristic, strictest wins, UNKNOWN = PROD) + per-capability matrix + time-boxed audited override + config-store identity check.**

## Decision
Option 2 (LLD-12 §2–3). Authoring, introspection, and playground beans are absent in PROD by default;
query preview/explain is never available in PROD; prod config changes arrive as signed bundles promoted from STAGE.

## Consequences
+ Minimal attack surface in prod; stage/prod mix-ups fail closed. − Developers must declare `tier=dev` locally (clear startup message); prod hotfixes to agent config go through promotion or break-glass.
