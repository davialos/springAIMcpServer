# springAIMcpServerCommon — Project Instructions

Embeddable Spring Boot library (starter JAR) that lets a **host** Spring Boot application
expose metadata-driven dynamic REST endpoints, safe dynamic database queries, Spring AI
agents and an MCP server that understand the host's own code (`@Ai*` semantic annotations + entity graph) — all
configured at runtime from an admin control plane and governed by the host's existing access management.

## Current phase: IMPLEMENTATION (started 2026-09-28)

- Design docs (`docs/`) are the specification. Implement against them; if code must deviate, update the
  LLD/ADR in the same commit and say why in the commit body.
- **Do not run Maven builds yet** (product owner decision 2026-09-28): Maven/Docker are not installed on the
  dev machine. Write code and tests so that they compile and pass once run; keep APIs verified against the
  versions below (check docs.spring.io / javadoc when unsure — never guess an API).
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
- `docs/security/*.md` · `docs/adr/*.md` · `docs/open-questions.md` · `docs/production-readiness.md`
- `docs/integration/host-integration-guide.md` — how host applications configure the starter

## Design rules (non-negotiable)

- **Writes:** never executed by a model — only user-reviewed, explicitly confirmed change proposals, applied through
  host write paths so host versioning/audit/identity flows record them (ADR-0009, LLD-11).
- **Host safety (LLD-12):** fail the feature, not the host; UNKNOWN tier = PROD; authoring/introspection off in PROD.
- **Default deny:** nothing reachable unless explicitly exposed by code annotations AND granted to the caller.
- Tools run **as the caller** through Spring proxies (ADR-0008); AI read tools run in a read-only scope (ADR-0014).
- Apply global rules: clean-architecture, release-it (timeouts, bulkheads), ddia (one owner per fact), code-complete.
