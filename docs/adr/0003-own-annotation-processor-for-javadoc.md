# ADR-0003: Own JSR-269 processor for code knowledge (not therapi-runtime-javadoc)

> **Superseded by ADR-0013 (2026-09-28).** The processor survives only as the optional `javadoc-enricher` (LLD-02 §6).
- Status: Proposed · Date: 2026-09-27

## Context
Javadoc is dropped by javac; agents need descriptions of entities/methods plus types, relations and security hints.

## Options considered
1. therapi-runtime-javadoc — mature, per-class JSON, reflection-based runtime API. Captures docs only; no selection model, no entity graph, no JSON schema.
2. Own processor — `Elements.getDocComment()` (handles `///` Markdown docs), emits one versioned, schema-validated catalog incl. entities, relations, operations, type schemas, classification.
3. Runtime source parsing (JavaParser on shipped sources) — requires shipping sources; rejected.

## Decision
Option 2; keep therapi as a possible doc-source fallback adapter.

## Consequences
+ Single artifact, opt-in selection, deterministic, reviewable in CI (diff). − We own a processor (Gradle incremental, multi-round, Kotlin later).
