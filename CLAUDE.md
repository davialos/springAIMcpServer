# springAIMcpServerCommon — Project Instructions

Embeddable Spring Boot library (starter JAR) that lets a **host** Spring Boot application
expose metadata-driven dynamic REST endpoints, safe dynamic database queries, Spring AI
agents and an MCP server that understand the host's own code (`@Ai*` semantic annotations + entity graph) — all
configured at runtime from an admin control plane and governed by the host's existing access management.

## Current phase: IMPLEMENTATION (started 2026-09-28)

- Design docs (`docs/`) are the specification. Implement against them; if code must deviate, update the
  LLD/ADR in the same commit and say why in the commit body.
- **Builds are allowed** (product owner decision 2026-09-30, superseding 2026-09-28): the reactor compiles and its
  unit + Testcontainers integration tests pass on JDK 25 (`openjdk-25-jdk-headless`) + Maven 3.9.x. Keep APIs
  verified against the versions below — when unsure, `javap` the resolved jar rather than guessing.
- **Offline build:** every dependency and Maven plugin is vendored in `offline-repo/`; run
  `scripts/build-offline.sh [-DskipITs]` (docs/offline-build.md). When a pom change adds or upgrades a dependency,
  refresh the repo in the same commit (procedure in that doc).
- Unresolved items go to `docs/open-questions.md` — never silently assume.

## Baseline (verified 2026-09-28, see docs/research/spring-ai-2-notes.md)

| Item | Version |
|------|---------|
| Java | 25 LTS (`maven.compiler.release=25`, `-parameters`) |
| Spring Boot | 4.1.1 (Spring Framework 7, Jackson 3 = `tools.jackson.*`, Hibernate 7, Jakarta Persistence 3.2) |
| Spring Security | 7.1.x (managed by Boot) |
| Spring AI | 2.0.1 (`ToolCallback`, `ToolCallingAdvisor`; `FunctionCallback` is legacy 1.x naming) |
| MCP Java SDK | 2.0 (spec 2025-11-25, Streamable HTTP) — managed by the Spring AI BOM |
| PostgreSQL | 15+ for the `dynamic_ai` store (ADR-0019, LLD-15) |
Boot 4 auto-configuration packages: `org.springframework.boot.jdbc.autoconfigure`, `org.springframework.boot.hibernate.autoconfigure`,
`org.springframework.boot.flyway.autoconfigure`, `org.springframework.boot.webmvc.autoconfigure`.

## Namespace rules (collision isolation with host)

- Config properties: `dynamic.ai.agent.*`
- DB: schema `dynamic_ai`, tables `dai_*`, Flyway history `dai_schema_history`, migrations `classpath:db/dynamic-ai/migration`
- HTTP: control plane `/dynamic-ai/admin/**`, data plane `/dynamic-ai/api/**`, MCP `/dynamic-ai/mcp`
- Project **springAIMcpServerCommon**; groupId + base package **`com.springaimcpservercommon`** (provisional, OQ-02b);
  artifactIds `spring-ai-mcp-server-common-*`
- Web Component tag prefix `saimcp-`; meters `dynamic.ai.agent.*`; spans `dai.*`

## Modules (ownership for parallel work — edit only your module + its pom)

| Module (artifactId suffix) | Package | Contents | Depends on |
|---|---|---|---|
| `bom` | — | version alignment for hosts | — |
| `annotations` | `…annotations` | `@AiContext`, `@AiEntityProperty`, `@AiExposedAction`, `@AiParam`, `@AiQueryConstraints`, `Classification` | nothing |
| `core` | `…core` | domain model, ports (SPI), catalog model, policy merge, invocation context, ids. **No Spring Web/JPA/AI imports** | annotations, jspecify, jackson (3) |
| `persistence` | `…persistence` | Flyway migrations, JPA entities, stores, audit hash chain, `DaiPersistenceUnit` | core |
| `security` | `…security` | principal mapping, authorization engine, API keys, filter chain pieces | core, persistence, spring-security |
| `query` | `…query` | dynamic query AST → Criteria compiler/executor over **host** entities | core |
| `ai` | `…ai` | agent runtime, tool bridge, advisors, write guard | core, spring-ai |
| `mcp` | `…mcp` | MCP server exposure, auth glue | ai, security |
| `webmvc` | `…webmvc` | dynamic endpoints, admin API, SSE streaming, problem details | core, security |
| `autoconfigure` | `…autoconfigure` | `@AutoConfiguration` classes + `@ConfigurationProperties` only | all above (optional) |
| `spring-boot-starter` | — | dependency aggregator | autoconfigure + defaults |
| `loadtest` | `…loadtest` | dev tool (not in the starter/BOM): Spring API discovery (sources, OpenAPI, actuator, HAR) → entity-relationship payloads + seeding → k6 suite generator, data providers, journeys, CLI (ADR-0022, LLD-16) | jackson 3, postgresql driver; **no Spring** |
| `loadtest-maven-plugin` | `…loadtest.maven` | dev tool: `mvn loadtest:discover/generate/run/compare` over the loadtest public API (ADR-0024) | loadtest, maven-plugin-api (provided) |
| `loadtest-junit` | `…loadtest.junit` | dev tool (test scope for hosts): `@K6LoadTest` JUnit 5 extension, `K6Suite` (ADR-0024) | loadtest, junit-jupiter-api (provided); **no Spring** |
| `loadtest-mcp` | `…loadtest.mcp` | dev tool: stdio MCP server with load-test tools for coding agents; plugin in `claude-plugins/spring-loadtest` (ADR-0024) | loadtest, MCP Java SDK 2.0; **no Spring** |
| `jfr-analyzer` | `…jfranalyzer` | developer CLI: JFR file → HTML/JSON hot-spot report (docs/tools/jfr-analyzer.md); not in the BOM | core (CanonicalJson only) |

## Coding conventions

- Java 25: records for values, sealed interfaces for closed hierarchies, pattern matching; no Lombok.
- Null-safety: JSpecify `@NullMarked` in every `package-info.java`; `@Nullable` where needed.
- No field injection; constructor injection; library classes are **not** `@Component` — beans are declared only in `autoconfigure`.
- Every default bean `@ConditionalOnMissingBean`; no global Spring side effects (LLD-12 §4); never register
  `EntityManagerFactory`, `TransactionManager`, `Flyway`, `ObjectMapper`/`JsonMapper` as beans (ADR-0019).
- Logging: SLF4J; never log prompt content, secrets or row data.
- IDs: UUIDv7 generated in Java (`core` `Ids.newId()`), `Instant` in UTC, money as `long` micros + ISO currency.
- Tests: JUnit 5 + AssertJ; PostgreSQL via Testcontainers 2 (`org.testcontainers:testcontainers-postgresql`).
- Javadoc on every public type/method of an SPI or public API.

## Commit conventions

- Small commits, one intent each: `<type>(<module>): <summary>` (types: feat, fix, build, docs, test, refactor).
- Body lists the concrete code changes (files/classes/tables) and the reason; reference LLD/ADR/F-IDs.
- End with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Commit messages via a message file (`git commit -F <file>`), not PowerShell here-strings.

## Doc map

- `docs/README.md` index · `docs/01-feature-catalog.md` · `docs/02-architecture-overview.md` · `docs/lld/*.md`
- `docs/lld/15-database-schema.md` — PostgreSQL schema (tables, keys, indexes, partitions, retention)
- `docs/lld/16-load-test-generator.md` — k6 load-test generator (`scripts/loadtest.sh`, `scripts/loadtest-mcp.sh`); guide `docs/integration/load-testing-guide.md`
- `docs/tools/jfr-analyzer.md` — JFR recording analyzer (`scripts/jfr-analyze.sh`)
- `docs/data/schema-usecase-map.md` — table → use case → business flow → feature/requirement map (behaviour; structure stays in LLD-15);
  regenerate with `scripts/schema-usecase-map/` + the `schema-usecase-mapper` agent
- `docs/security/*.md` · `docs/adr/*.md` · `docs/open-questions.md` · `docs/production-readiness.md`
- `docs/integration/host-integration-guide.md` — how host applications configure the starter

## Design rules (non-negotiable)

- **Writes:** never executed by a model — only user-reviewed, explicitly confirmed change proposals, applied through
  host write paths so host versioning/audit/identity flows record them (ADR-0009, LLD-11).
- **Host safety (LLD-12):** fail the feature, not the host; UNKNOWN tier = PROD; authoring/introspection off in PROD.
- **Default deny:** nothing reachable unless explicitly exposed by code annotations AND granted to the caller.
- Tools run **as the caller** through Spring proxies (ADR-0008); AI read tools run in a read-only scope (ADR-0014).
- Apply global rules: clean-architecture, release-it (timeouts, bulkheads), ddia (one owner per fact), code-complete.
- **One canonical-JSON writer:** `core.json.CanonicalJson` (ADR-0020). Never write a second value-tree JSON
  renderer — delegate to it, the way `persistence.support.CanonicalJson` and `persistence.config.CanonicalSpec` do.

## Scalability defaults (ADR-0021 — apply to every new component)

- **Stateless request handling by default.** Cross-replica state lives in PostgreSQL (the single source of
  truth already, LLD-15) or is recomputed from an immutable in-memory snapshot (effective catalog, compiled
  plans); never in server-local session state unless a capability genuinely requires it (documented exception:
  MCP's optional stateful mode, opt-in, not default).
- **No sticky sessions required by default.** Any host must be able to run N replicas behind a plain
  round-robin load balancer. MCP defaults to `STATELESS` transport for this reason.
- **No new mandatory infrastructure.** PostgreSQL is the one thing every host already runs for this library
  (ADR-0019); default implementations of anything shared (rate limits, budget counters) build on it rather
  than requiring Redis/Hazelcast/etc.
- **Escape hatch via ports, not hardcoding.** Anything that could become a throughput bottleneck at real
  scale (shared counters, caches) is behind an SPI with a `@ConditionalOnMissingBean` PostgreSQL default, so
  a host at scale can supply a faster backend without a code change here.
- **Bulkheads and caps over unbounded pools** (already LLD-12/LLD-14): scaling out means adding a node, not
  retuning a shared limit.
