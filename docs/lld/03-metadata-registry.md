# LLD-03: Metadata Registry & Policy Resolution Chain

| Field | Value |
|-------|-------|
| Status | Draft v2 (2026-09-28: sources are runtime annotations + JSON policy layers) |
| Owner agent | metadata-extraction-designer |
| Module(s) | `core` (model, resolver, ports), `jpa`/`ai` (resolvers) |
| Related features | F-10 … F-15, F-73 |
| Related ADRs | ADR-0013 |

## 1. Purpose & responsibilities
Hold the single, immutable, effective view of "what the AI may see and do in this host": the scanned
catalog (LLD-02) **merged** with external policy layers (classpath/file JSON, dashboard overlays, kill
switches), resolved against live beans and the JPA metamodel. Lock-free reads for every request.

## 2. Contracts
```java
// design sketch
public interface MetadataRegistry {                          // host may replace (@ConditionalOnMissingBean)
    EffectiveCatalog current();                              // immutable snapshot; never null
}
public record EffectiveCatalog(long generation, String scanFingerprint, String policyFingerprint,
        Map<CatalogElementRef, EffectiveEntity> entities,
        Map<CatalogElementRef, EffectiveOperation> operations,
        List<ResolutionIssue> issues) {
    Optional<EffectiveEntity> entity(CatalogElementRef ref);
    Optional<EffectiveOperation> operation(CatalogElementRef ref);
    List<RelationPath> relationPaths(CatalogElementRef from, int maxDepth);
}
public record EffectiveOperation(CatalogElementRef ref, String toolName, String description,
        List<String> keywords, List<ParamDescriptor> params, JsonSchema inputSchema,
        boolean readOnly, boolean idempotent, Classification classification,
        boolean enabled, List<PolicyProvenance> provenance,     // which layer set which value (shown in UI)
        ResolvedInvocationTarget target) {}                     // bean name + interface Method (proxy-safe)
public interface PolicySource {                               // SPI: one per layer
    PolicyLayer layer(); PolicyDocument load();               // PolicyDocument = parsed, validated JSON
}
```

## 3. Resolution against the live application
| Element | Resolved against | On failure |
|---------|------------------|------------|
| Entity / attribute | `Metamodel.entity(Class)` / `getAttribute(name)` | issue; element unusable |
| Operation | bean name recorded at scan time → still present & same type | issue; operation disabled |
`ResolvedInvocationTarget` keeps the **bean name and interface-level `Method`**, so calls always go
through the Spring proxy (security, transactions, auditing) — never the raw target (ADR-0008).

## 4. Policy resolution chain (dual control: code + configuration)
```
 L0  Code annotations (@AiContext, @AiExposedAction, @AiEntityProperty, @AiQueryConstraints)   ← developer defaults
  │
 L1  Classpath / file policy JSON   dynamic.ai.agent.policy.locations
  │     default: classpath:META-INF/dynamic-ai/ai-agent-policy.json, optional file:/config/ai-agent-policy.json
  │     (per-environment files via Spring profiles or config server; GitOps-friendly, no DB needed)
  │
 L2  Dashboard overlays   published, versioned, approved revisions in dai_* (LLD-09)
  │
 L3  Kill switches        runtime, cluster-wide ≤ 10 s (F-73)
  ▼
 EffectiveCatalog (generation n)  → LLD-07 builds ToolCallbacks per request from it
```

### 4.1 Merge rules (per attribute; "restrictive wins" for safety, "latest layer wins" for text)
| Property | Rule | Consequence |
|----------|------|-------------|
| Exposure | **Only L0 can expose.** L1/L2 can reference only elements that exist in the scan | Config can never turn an arbitrary host method into an AI tool |
| `enabled` | Logical AND across layers | Any layer can disable; none can re-enable what a stricter layer disabled |
| `readOnly` | Can only become *more* restrictive; `false → disabled` allowed, `true → false` rejected | A JSON file can't make a read action writable |
| `description`, `intent`, `keywords`, `meaning` | L2 > L1 > L0 (non-empty wins) | Tuning prompts without redeploying; original kept for diff |
| `sensitive`, `classification` | max across layers; lowering only in L2 with `catalog:declassify` + approval | — |
| `maxLimit` | min across layers and global cap | — |
| `mandatoryFilters` | union | — |
| Tool `name` | L0 only | Stable tool names for evals and audit |

### 4.2 Policy JSON (schema `https://dynamic-ai/schemas/policy/1.json`)
```json
{
  "schemaVersion": 1,
  "overrides": {
    "op:com.host.app.UserService#deleteUser(java.lang.Long)": {
      "enabled": false,
      "reason": "Disabled due to AI hallucination risk."
    },
    "com.host.app.OrderService.calculateDiscount": {
      "descriptionOverride": "Calculates B2B discounts. Do not use this for retail customers."
    },
    "entity:com.host.app.Customer": { "maxLimit": 20, "mandatoryFilters": ["tenantId"] },
    "attr:com.host.app.Customer#taxId": { "sensitive": true }
  }
}
```
- Keys are canonical `CatalogElementRef`s. The shorthand `Class.method` from the design note is
  accepted **only if unambiguous**; an overloaded method in shorthand is a validation error listing the
  canonical refs to use instead.
- Unknown keys (element not in the scan) → issue `POLICY_REF_UNKNOWN` (warning; error with `strict`).
- `reason` is mandatory when `enabled=false`; shown in the dashboard and audit.

### 4.3 Failure semantics (fail closed)
| Situation | Behavior |
|-----------|----------|
| L1 file missing (optional location) | Layer empty, INFO |
| L1 file present but invalid JSON / schema | **All AI tools disabled** (the file may contain disables that must not be lost), health DOWN for our contributor, CRITICAL log. Data-plane endpoints without AI unaffected |
| L2 store unreachable | Keep last-good L2 layer in memory (LLD-09 §6) |
| L1 `file:` location changes on disk | Re-read on the snapshot poll cycle when its hash changes → new generation |

## 5. Lifecycle & concurrency
Scan once at startup (LLD-02 §3.1); every change in L1/L2/L3 rebuilds a new `EffectiveCatalog`
generation off-thread and swaps it via `AtomicReference`. Readers never lock. Tool callbacks are built
per request from the current generation, so a disable takes effect on the next agent turn.

## 6. Drift detection
Published resources pin `scanFingerprint` refs + per-element signature hash. After each deploy, a mismatch
(removed action, changed signature, `readOnly` flipped, sensitivity lowered) → `DriftFinding`; policy
`dynamic.ai.agent.catalog.on-drift = WARN | SUSPEND_RESOURCE | FAIL_STARTUP` (default `SUSPEND_RESOURCE`).

## 7. Security
- Catalog exposure through the admin API requires `catalog:read` and is disabled in PROD (LLD-12 §2.2).
- Agent prompts include only descriptors of tools that are enabled AND granted to the caller.
- Every layer change is audited with provenance (who/which file hash).

## 8. Observability
Gauges `dynamic.ai.agent.catalog.elements{kind,state}`, `…policy.layer.valid{layer}`; counter of drift
findings; span `dai.catalog.build`. Info contributor: scan and policy fingerprints.

## 9. Test strategy
Merge-rule property tests (random layer combinations never make an element *less* restricted than any
single layer demands); invalid-file fail-closed test; overload shorthand rejection; concurrent swap under load.
