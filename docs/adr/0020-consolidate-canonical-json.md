# ADR-0020: Consolidate canonical JSON on a single writer (`core.json.CanonicalJson`)
- Status: **Accepted and applied** (2026-09-29) · Resolves OQ-32

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
`core.json.CanonicalJson` is now the single writer. Applied:
1. Added `UUID` support to `core.json.CanonicalJson` (writes as its string form) — it is now a strict
   superset of what the two persistence writers supported.
2. Kept the U+2028/U+2029 escaping (`core`'s original behavior) as the one rule; nothing was deployed yet,
   so there was no existing hash to stay compatible with. Also added a `MAX_DEPTH` (128) guard to **both**
   `write` and `immutableCopy` — the pre-existing depth guard lived only in the persistence parser, so a
   Java-constructed tree handed straight to the writer (as `AuditCanonicalForm` and `ChangeProposal` do) was
   previously unguarded against unbounded recursion.
3. `persistence.support.CanonicalJson` keeps its parser (`parse`, `canonicalize`, `canonicalizeObject`)
   untouched; `write`/`quote` now delegate to `core.json.CanonicalJson.write` — the five private
   `writeValue`/`writeObject`/`writeString`/`finite`/`canonicalNumber` methods were removed entirely.
4. `persistence.config.CanonicalSpec` keeps its Jackson-based **parsing** untouched (strict,
   `USE_BIG_DECIMAL_FOR_FLOATS`, `FAIL_ON_TRAILING_TOKENS`); its own `write`/`writeString` methods were
   removed, `plain(BigDecimal)` is kept but now used only as a `MAX_NUMBER_DIGITS` size check via a new
   `checkNumberSizes` walk over the parsed tree, and rendering delegates to `core.json.CanonicalJson.write`.
5. Added a direct unit test for `core.json.CanonicalJson` (it previously had none of its own — it was only
   exercised indirectly through catalog/policy fingerprint tests) covering key sorting, `UUID`, number
   normalisation, U+2028/U+2029 escaping, the new depth guard (`write` and `immutableCopy`), and rejection of
   unsupported/non-finite values. Added one cross-module regression test in each of
   `CanonicalSpecTest` and `persistence.support.CanonicalJsonTest` asserting their output is byte-identical
   to `core.json.CanonicalJson.write` for an equivalent value tree, so a future change can't silently
   re-diverge them without a test failing.

## Verification
No Maven/Testcontainers available yet (unchanged constraint), but this change touches only value-tree
writing logic with no Spring/JPA/DB dependency, so it was verified for real: compiled with `javac` against
the real JDK 25 and a working Jackson-3-shaped stub (extended, for this check, with an actual small JSON
parser rather than a no-op stand-in, so `CanonicalSpec`'s Jackson-touching code path executes real parsing
logic end to end), then every assertion in the three existing, already-committed test files
(`CanonicalJsonTest` in both `core` and `persistence.support`, and `CanonicalSpecTest`) was replayed
literally against the consolidated code and passed byte-for-byte, including hash values, idempotence, the
jsonb-round-trip case, and every rejection case (invalid JSON, non-objects, oversized numbers, mismatched
hash). The new UUID and depth-guard behavior was verified the same way. The only thing this verification
does not cover is Jackson 3's real parser byte-for-byte (the stub's parser is hand-written, not Jackson
itself) — that gap is identical to what existed before this change, since the parsing code itself was not
modified.

## Consequences
+ One place now decides "what canonical JSON looks like"; the two persistence classes are back down to
  their genuinely distinct value-add (parsing, and Jackson-strict parsing with a size guard). A
  Java-constructed value tree is now depth-guarded everywhere, not only when it came from the hand-written
  parser.
− Anyone editing `core.json.CanonicalJson`, `persistence.support.CanonicalJson` or
  `persistence.config.CanonicalSpec` should re-read this ADR first; the two cross-module regression tests
  will fail if the delegation is ever accidentally reverted.
