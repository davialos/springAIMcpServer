# LLD-12: Host Safety & Environment Containment

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | lld-chief-architect (with access-management-architect, production-readiness-reviewer) |
| Module(s) | `core` (policy), `autoconfigure` (conditions, failure analyzers), all adapters (fault isolation rules) |
| Related features | F-01, F-04, F-73, F-74, F-75 |
| Related ADRs | ADR-0001, ADR-0011, ADR-0012 |
| Input | Product-owner design note "Embeddable JAR production safeguards" (2026-09-27) — evaluated in §9 |

## 1. Purpose & responsibilities
The library shares the host's JVM, heap, thread pools, DB connections, and Spring context.
This LLD defines the rules that stop it from harming the host:
(a) **environment containment** — which capabilities exist in which environment;
(b) **cross-environment guards** — stop stage config/data leaking into prod and the reverse;
(c) **fault isolation** — no exception, thread, memory, or classpath effect leaks into the host;
(d) **data-availability probes** — only expose what is actually backed by a healthy data source.

## 2. Environment model
```java
// design sketch
public enum EnvironmentTier { DEV, TEST, STAGE, PROD, UNKNOWN }
public record EnvironmentIdentity(EnvironmentTier tier, String environmentId /* e.g. "orders-prod-eu" */,
                                  Source source /* EXPLICIT | PROFILE_HEURISTIC | DEFAULT */) {}
public interface EnvironmentSafetyPolicy {                 // Strategy; host may replace (@ConditionalOnMissingBean)
    EnvironmentIdentity identify(Environment env);
    boolean isEnabled(Capability capability, EnvironmentIdentity id);
}
```

### 2.1 Tier resolution (composite; strictest wins)
| # | Signal | Rule |
|---|--------|------|
| 1 | `dynamic.ai.agent.environment.tier` (explicit) | Preferred. |
| 2 | Active profiles matched against `dynamic.ai.agent.environment.prod-profile-patterns` (default `prod, production, live, prd, *-prod`), case-insensitive | If any profile matches → at least PROD |
| 3 | Conflict: explicit says DEV/TEST/STAGE but a prod profile is active | **Treat as PROD**, log CRITICAL, audit `ENVIRONMENT_CONFLICT` (a leaked profile or a leaked property — either way fail closed) |
| 4 | Nothing set | `UNKNOWN` ⇒ handled **as PROD** (fail closed). Startup log explains how to declare `tier=dev` locally |

Profiles alone are never trusted to *unlock* anything; they can only make the tier stricter.

### 2.2 Capability matrix (defaults)
| Capability | DEV | TEST / STAGE | PROD (default) | PROD + override |
|------------|:---:|:---:|:---:|:---:|
| Data plane: published endpoints & agents (read) | ✔ | ✔ | ✔ | ✔ |
| Reviewed writes (LLD-11), if `write.enabled` | ✔ | ✔ | ✔ | ✔ |
| MCP server | ✔ | ✔ | ✔ (if enabled) | ✔ |
| Ops views: usage, audit, kill switches, cluster status | ✔ | ✔ | ✔ | ✔ |
| **Authoring**: drafts, endpoint/query/agent builders | ✔ | ✔ | ✘ | ✔ time-boxed |
| **Introspection**: catalog browser, entity graph, JSON schemas | ✔ | ✔ | ✘ (PLATFORM_ADMIN read-only summary only) | ✔ time-boxed |
| **Query preview / explain / generated SQL** | ✔ | ✔ | ✘ | ✘ (never in prod) |
| **Playground** against live data | ✔ | ✔ | ✘ | ✔ time-boxed, runs as author |
| Config changes | UI or bundle | UI or bundle | **signed bundle import only** (promotion from STAGE, LLD-09 §5) | UI, approval mandatory |

Disabled capabilities are removed at **bean level** (no controller, no route ⇒ no attack surface) via
`@ConditionalOnCapability(Capability.AUTHORING)` → `EnvironmentCapabilityCondition` (a Spring `Condition`
that delegates to the default policy). Capabilities that must stay as beans (e.g. the admin API that also
serves ops views) are checked again per request by `EnvironmentSafetyPolicy` (defence in depth).

### 2.3 Production override (break-glass)
```yaml
dynamic.ai.agent.environment:
  tier: prod
  production-override:
    enabled: true
    capabilities: [AUTHORING, PLAYGROUND]
    expires-at: 2026-10-01T18:00:00Z     # mandatory; override rejected at startup if missing or > 72h away
    reason: "INC-4412 hotfix of agent prompt"
```
On activation: log CRITICAL; audit `PRODUCTION_OVERRIDE_ENABLED` (who deployed is unknown here, so
the config fingerprint and host version are recorded); banner in the admin UI; every action taken under
the override is audited with `override=true`; approval policy forced to four-eyes. When `expires-at` passes,
the capability turns off **at runtime** (per-request policy check) without a restart. A DB-driven
just-in-time elevation (F-68) is the v2 replacement for this config-based break-glass.

## 3. Cross-environment guards (config & data leakage)
| Guard | Mechanism | On violation |
|-------|-----------|--------------|
| **Config-store identity** | `dai_environment` row (environmentId, tier) written on first migration. At startup: the store's identity must equal this app's identity | Refuse to load snapshots; data plane serves empty; health DOWN (ours); CRITICAL log. Stops a stage app reading the prod config DB and the reverse |
| **Bundle promotion order** | Bundle manifest carries source tier/environmentId; import allowed only along `dynamic.ai.agent.environment.promotion-path` (default DEV→TEST→STAGE→PROD) and only if signed by a trusted key for that path | Import rejected (422) + audit |
| **Property validation** | `@ConfigurationProperties` + `@Validated` (Jakarta Validation) on all our property classes; custom validators: base-path pattern, URL allow-lists, duration bounds, tier-specific rules (e.g. PROD forbids `allow-insecure`, `record-content=true`, the `in-memory` rate-limit backend when replicas > 1) | Startup fails with a `FailureAnalyzer` message *only for our feature*; see §4 on "fail feature, not host" |
| **Datasource / provider pinning** (optional) | `environment.guards.datasource-url-patterns`, `environment.guards.model-endpoint-patterns` per tier (e.g. PROD must match `jdbc:postgresql://orders-prod-*`) | Feature disabled + CRITICAL log |
| **Config fingerprint** | SHA-256 over our effective (non-secret) properties, logged at startup, exposed in `/actuator/info` contribution and audit | Lets ops compare prod vs stage config without dumping it |
| **Secrets** | Never in our properties; references resolved via `SecretResolver` SPI (host vault / Spring Cloud Config / env) | Plain-text key-like values in our properties → startup warning (error in PROD) |

## 4. Fault isolation rules (the library must never take the host down)
| Area | Rule |
|------|------|
| Startup | Default policy **"fail the feature, not the host"**: an exception in our initialisation disables that feature and marks our health contributor DOWN. `dynamic.ai.agent.startup.fail-fast=true` makes it fatal (recommended in CI). We never call `System.exit` or `SpringApplication.exit` |
| Request handling | All our handlers catch at the boundary and map to RFC 9457 problems. Our `@RestControllerAdvice` is scoped with `assignableTypes`/`basePackageClasses` to our controllers only, so it never changes the host's error handling |
| Global Spring hooks | Forbidden: global `ObjectMapper`/`JsonMapper` customizers, global `WebMvcConfigurer` converters, `HandlerInterceptor`s or `Filter`s without a path matcher for `/dynamic-ai/**`, `BeanPostProcessor`/`BeanFactoryPostProcessor` that modify host beans, `@ComponentScan`, `@EnableJpaRepositories` over host packages, `@Primary` beans. ArchUnit and context tests enforce this |
| Threads | No work on host servlet threads beyond dispatch: LLM, tool, and MCP I/O run on our virtual-thread executor, **bounded by semaphores** per downstream (virtual threads remove thread starvation, not downstream overload). Named threads `dai-*`; graceful shutdown hooked into `SmartLifecycle` with a timeout |
| DB connections | Dynamic queries run in their own read-only transaction on our executor thread (no `REQUIRES_NEW` nested inside a host tx, which would hold two connections and risk pool deadlock). Concurrency is capped below the pool size (`query.max-concurrent` ≤ pool × 0.25 by default, validated at startup). An optional dedicated read-replica `DataSource` isolates load fully |
| Memory | Everything bounded: routes (`endpoints.max-routes`, default 500; publish rejected above it, **never evict live routes**), caches (Caffeine with max size + TTL), result rows (`query.max-rows`), tool result chars, conversation window, SSE buffers, proposal payloads. Route registration creates no classes, so the risk is heap (mapping registry), not Metaspace; unpublish always calls `unregisterMapping` |
| Classpath | See ADR-0012: never shade Spring-managed libraries (Jackson 3, Micrometer, Spring AI, Hibernate); inherit versions from the Spring Boot BOM. Shade/relocate only small internal-only libraries with no Spring integration (candidate: JSON-schema validator) into `…springaimcpservercommon.internal.shaded`. Keep the dependency footprint minimal and document it in the BOM |
| Reflection | Only on catalog-resolved, allow-listed members; never `setAccessible` on host private members; invocation always through public interfaces on Spring proxies |
| Kill switch | Global `dynamic.ai.agent.enabled=false` at deploy time; runtime global kill switch (F-73) turns off all data-plane traffic within 10 s without a restart |

## 5. Data-availability probes
Before a published resource is served, and periodically (default 30 s):
1. `DataSource` liveness via `Connection.isValid(timeout)` (JDBC-native, dialect-independent) on the
   datasource(s) that back the resource, with a 2 s timeout and its own circuit breaker.
2. Metamodel check: every referenced entity is still present in `EntityManagerFactory.getMetamodel()`
   (reuses LLD-03 resolution; drift → resource suspended).
3. Result is per resource: `AVAILABLE | DEGRADED | UNAVAILABLE`. Unavailable resources return
   503 `data-source-unavailable` fast (no pile-up) and are hidden from agent tool lists for that turn
   (the model is told the tool is temporarily unavailable instead of timing out).
4. Exposed as details of our health contributor, not in the host's readiness group by default.

## 6. Configuration
| Property | Default |
|----------|---------|
| `dynamic.ai.agent.environment.tier` | unset (⇒ UNKNOWN ⇒ treated as PROD) |
| `dynamic.ai.agent.environment.id` | `${spring.application.name}-${tier}` |
| `dynamic.ai.agent.environment.prod-profile-patterns` | `prod,production,live,prd,*-prod` |
| `dynamic.ai.agent.environment.production-override.*` | disabled |
| `dynamic.ai.agent.environment.promotion-path` | `DEV,TEST,STAGE,PROD` |
| `dynamic.ai.agent.environment.guards.*` | none |
| `dynamic.ai.agent.startup.fail-fast` | `false` |
| `dynamic.ai.agent.endpoints.max-routes` | `500` |
| `dynamic.ai.agent.availability.probe-interval` | `30s` |

## 7. Observability & audit
Audit events: `ENVIRONMENT_IDENTIFIED`, `ENVIRONMENT_CONFLICT`, `PRODUCTION_OVERRIDE_ENABLED/EXPIRED`,
`CAPABILITY_DENIED_BY_ENVIRONMENT`, `CONFIG_STORE_IDENTITY_MISMATCH`, `FEATURE_DISABLED_ON_STARTUP_ERROR`.
Gauges: `dynamic.ai.agent.capability.enabled{capability}`, `dynamic.ai.agent.resource.availability{state}`.
Info contributor: tier, environmentId, config fingerprint, catalog hash, snapshot generation.

## 8. Test strategy
- `ApplicationContextRunner` matrix: tier × profiles × override → expected bean presence (authoring beans absent in PROD).
- Conflict tests: `tier=dev` + profile `prod` ⇒ PROD behaviour + audit event.
- Override expiry test with a fixed `Clock`.
- Chaos tests: exception thrown inside each of our initialisers ⇒ host context still starts and host endpoints still serve.
- Context test proving no global side effects: host `ObjectMapper`, error handling, filters, and MVC config are identical with and without our starter.
- Pool-starvation test: saturate dynamic queries ⇒ host repository calls keep their latency SLO.

## 9. Evaluation of the input design note
| Proposal in note | Verdict | Reason / what we do instead |
|------------------|---------|-----------------------------|
| Profile-based `StrictEnvironmentCondition` + prod override flag | **Adopted, strengthened** | Composite tier resolution (§2.1); UNKNOWN = PROD; profiles can only tighten; override is per capability, time-boxed, audited (§2.3) |
| Lock endpoints / serve read-only in prod | **Adapted** | Per-capability matrix (§2.2): published data plane must run in prod (it is the product); authoring, introspection, and preview are what get locked |
| `@Validated` `@ConfigurationProperties` | **Adopted** | §3, with tier-specific validators |
| `BeanPostProcessor` checking properties with cryptographic checksums | **Rejected** | A BPP over host beans breaks our no-side-effect rule, and checksums over config don't prove correctness. Replaced by validators, config-store identity, datasource pinning, and a config fingerprint (§3) |
| Build-time processor → JSON → immutable registry; degrade gracefully if missing | **Superseded** by runtime annotations (ADR-0013) | Registry now built from a startup scan + policy layers (LLD-02/03); still degrades gracefully |
| `SandboxedDataExecutor` taking **JPQL strings** validated with JSqlParser | **Rejected** | Contradicts ADR-0004: strings from LLMs/admins are the injection vector, and JSqlParser parses SQL, not JPQL/HQL. We keep the structured AST → Criteria API with row policies. Adopted from it: read-only tx, timeout, row cap |
| `REQUIRES_NEW` read-only transaction | **Adapted** | Own tx on our own executor thread instead (§4 DB connections), to avoid the two-connection nested-tx deadlock |
| `SELECT 1` + metamodel availability probes | **Adopted** | Using `Connection.isValid` + metamodel, per resource, with breaker (§5) |
| `@ConditionalOnMissingBean` override architecture | **Already designed** (LLD-01) | — |
| Default dashboard authorizer trusting `X-Origin-Zone: local` header | **Rejected (security)** | Client-controlled headers are spoofable; authorization is always via host Spring Security (SEC-01). Network-zone restrictions belong to the host's ingress, and can be added as an ABAC condition on trusted, gateway-set attributes only |
| Isolated executor / virtual threads | **Adopted** | Virtual threads + semaphore bulkheads (§4, ADR-0007) |
| Shade/relocate Jackson, Guava, … | **Adapted** (ADR-0012) | Shading Spring-integrated libraries breaks auto-configuration and doubles heap/classes; only internal-only libs may be relocated |
| LRU cache for route mappings (Metaspace leak) | **Adapted** | Evicting live routes would silently break endpoints; use a hard route quota + mandatory unregister. The risk is heap, not Metaspace (§4) |
| Masking visitor for `@JsonIgnore`, `@Transient`, `@Sensitive` | **Adopted, extended** | Catalog treats `@JsonIgnore`/`@Transient` as not-exposed by default, plus `@AiEntityProperty(sensitive=true)` and a configurable name heuristic (`password|secret|token|apiKey|ssn|…`) flagged at scan time, requiring explicit confirmation (LLD-02 §4) |
| Micrometer metrics to host stack | **Already designed** (LLD-10) | Meter prefix `dynamic.ai.agent.*` (the note's `agent.*` names would collide more easily) |
| Property prefix `agent.platform.*` | **Not adopted** | Keeps the brief's `dynamic.ai.agent.*` until OQ-17 decides the final namespace |

## 10. Open questions
OQ-17 (namespace), OQ-19 (should UNKNOWN tier be treated as PROD, or require the explicit tier to start?).
