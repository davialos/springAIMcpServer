# ADR-0009: Writes only via user-reviewed change proposals applied through host write paths
- Status: Accepted (product owner, 2026-09-27) · Resolves OQ-12

## Context
Write capability is required, but every write must be explicitly confirmed by the user after reviewing it in UI components. The host already has versioning tables, audit tables and identity-driven auditing (e.g. Envers, history tables, `AuditorAware`, triggers); these must remain the system of record for data history.

## Options considered
1. Model-executed mutating tools with a yes/no HITL prompt — user confirms an opaque call, no diff, no version check.
2. Own shadow/versioning tables maintained by the framework — duplicates host history, two owners of one fact.
3. **Proposal pattern:** mutating tools create a `ChangeProposal` (before-snapshot + version token, after-values, diff, validation); UI components render it; user confirms via an authenticated HTTP request; framework applies through host service methods (or JPA `EntityManager` for allow-listed entities) as the user, so host versioning/audit/identity flows fire unchanged.

## Decision
Option 3 (see LLD-11). Native SQL / bulk Criteria updates are forbidden for writes because they bypass host listeners and Envers.

## Consequences
+ Host history/audit stays the single owner of data history and records the real user; optimistic conflicts detected; no prompt-injection path to a write; AI-assisted changes are traceable (proposal ↔ host revision).
− Extra round trip for users; needs VersioningAdapter per host mechanism; proposals must be stored (short retention).
