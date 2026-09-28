# ADR-0020: Consolidate canonical JSON on a single writer (`core.json.CanonicalJson`)
- Status: Proposed (found during wave-2 merge review, 2026-09-29) · Date: 2026-09-29

## Context
Three independent implementation streams each needed a canonical (deterministic, hash-stable) JSON
rendering and, working in parallel, each wrote their own:

| Class | Module | Has a parser | Used by |
|---|---|---|---|
| `core.json.CanonicalJson` | core | no (write-only) | `CatalogCanonicalForm` (scan fingerprint), `JsonSchema`, policy document hashing |
| `persistence.support.CanonicalJson` | persistence | yes (hand-written) | `AuditCanonicalForm` (audit chain hash), `ChangeProposal` (proposal payload hash) |
| `persistence.config.CanonicalSpec` | persistence | yes (Jackson 3) | `ResourceRevision` (`spec_hash`) |

Comparing them line by line surfaced real, provable divergences, not just style differences:
1. **U+2028/U+2029 (Unicode line/paragraph separator):** `core` escapes them as ` `/` `; the two
   persistence writers pass them through literally. The same logical string containing one of these
   characters hashes differently depending on which writer processed it.
2. **`UUID` support:** `persistence.support.CanonicalJson` writes a bare `UUID` as its string form (used by
   `AuditCanonicalForm`); `core.json.CanonicalJson` has no `UUID` case and would throw
   `IllegalArgumentException` on the same value.
3. **Number-size guard:** `CanonicalSpec` rejects numbers whose plain-decimal form exceeds 200 digits (a
   guard against a crafted `1e999999999`-style value in admin-submitted JSON); neither `CanonicalJson`
   class has an equivalent limit.

All three independently converge on the same core rule (object keys sorted by UTF-16 code unit, no
insignificant whitespace, numbers as plain decimals via `BigDecimal.stripTrailingZeros().toPlainString()`)
— so the fix is narrow — but "one owner per fact" (this project's own DDIA rule) is violated for a
correctness property the whole versioning/audit integrity model depends on: a spec, an audit event or a
proposal payload must hash identically no matter which code path computed it, forever, including after a
`jsonb` round trip.

## Decision
`core.json.CanonicalJson` becomes the single writer. Concretely (not yet applied — see below):
1. Add `UUID` support to `core.json.CanonicalJson` (write as its string form), making it a strict superset
   of what the two persistence writers support today.
2. Keep the U+2028/U+2029 escaping (`core`'s current behavior) as the one rule; nothing is deployed yet, so
   there is no existing hash to stay compatible with.
3. `persistence.support.CanonicalJson` keeps its parser (`parse`, `canonicalize`, `canonicalizeObject`) —
   `core` has no parser and doesn't need one for its own callers — but its `write`/`quote` delegate to
   `core.json.CanonicalJson.write` instead of re-implementing `writeValue`/`writeString`/`canonicalNumber`.
4. `persistence.config.CanonicalSpec` keeps its Jackson-based **parsing** (strict, `USE_BIG_DECIMAL_FOR_FLOATS`,
   `FAIL_ON_TRAILING_TOKENS`) and its `MAX_NUMBER_DIGITS` pre-check on the parsed tree, but delegates
   **writing** to `core.json.CanonicalJson.write` instead of its own `write`/`plain`/`writeString`.
5. Add a cross-module test (in whichever module can depend on both, or duplicated as a golden-file fixture
   in each) asserting `core.json.CanonicalJson.write(x)`, `persistence.support.CanonicalJson.canonicalize(json)`
   and `persistence.config.CanonicalSpec.of(json).json()` produce byte-identical output for the same logical
   content — so a future change to one can't silently diverge from the others again.

## Why not applied in this pass
This is cryptographic-adjacent code with three existing, independently-passing test suites, and the dev
environment cannot currently run Maven/Testcontainers to verify a hand-edit compiles and the tests still
pass (product-owner decision, temporary). Hand-patching hashing logic across three files without a build to
check it is a higher risk than leaving three individually-correct implementations in place for one more
review cycle. This ADR exists so the divergence is tracked and fixed deliberately, with a real build/test
run, rather than papered over or forgotten. See OQ-32.

## Consequences
+ Once applied: one place decides "what canonical JSON looks like"; the two persistence classes shrink to
  their genuinely distinct value-add (parsing, and Jackson-strict parsing with a size guard).
− Until applied: the three writers must be kept manually in sync if either is touched again — anyone editing
  `core.json.CanonicalJson`, `persistence.support.CanonicalJson` or `persistence.config.CanonicalSpec`
  should re-read this ADR first and check the other two.
