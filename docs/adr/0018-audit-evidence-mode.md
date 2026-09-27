# ADR-0018: Tiered audit — standard (metadata + hashes) always, full-content evidence mode opt-in and encrypted
- Status: Proposed · Date: 2026-09-28

## Context
A design note asked to log the exact prompt, the generated query, returned rows and final prompt for every AI action, for a
legally defensible trail. That also copies regulated data (GDPR, HIPAA, PCI) into log pipelines without source-level access
control, retention, or erasure.

## Options considered
1. Log full content always (note's proposal).
2. Never keep content — weak evidence when an AI incident must be reconstructed.
3. **Standard tier always (actors, decisions, filters, row counts, entity IDs, result hashes, hash-chained); evidence tier with full
   content opt-in per workspace/agent, envelope-encrypted with a host KMS key, auditor-only access (itself audited), own retention,
   legal hold, crypto-shredding per data subject.**

## Decision
Option 3 (LLD-10 §4.1).

## Consequences
+ Defensible trail with minimised data by default; regulated teams can turn on full evidence with proper controls.
− Evidence mode adds storage and KMS dependency; hash verification needs access to the source or the evidence store.
