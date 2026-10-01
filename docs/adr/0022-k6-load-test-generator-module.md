# ADR-0022: k6 load-test generator as a separate developer-tool module
- Status: Accepted (2026-10-01)
- Deciders: product owner (request), implementation

## Context
The product owner asked for automated Grafana k6 load testing that can be pointed at a Spring Boot project and
works out the rest itself: discover the APIs the project exposes, write each API's request payloads, fill them
with dummy, random, real (checked against the database) or user-supplied data, and run load modes such as
smoke, spike, mixed-API spike, stress and mixed-API stress. It should be its own module, with data providers
generated per project and per API.

Constraints from this repository: the starter must stay free of anything a host does not need at runtime
(ADR-0001, ADR-0012); every dependency must already be vendored in `offline-repo/` or be added with a refresh;
writes are never made on shared data without an explicit decision (spirit of ADR-0009, LLD-12 "UNKNOWN tier =
PROD").

## Options considered
1. **Runtime feature inside the starter** (an admin endpoint that generates scripts from the live catalog).
   Sees runtime routes, but puts a test tool and JDBC sampling into every production host. Rejected (ADR-0001,
   LLD-12).
2. **Hand-written k6 scripts per project.** No generator to maintain, but every API change is manual and the
   data side (realistic, valid, real ids) is where most of the effort goes. Rejected — it is what was asked to
   be automated.
3. **A separate module `spring-ai-mcp-server-common-loadtest` with a CLI** that reads the project (sources,
   OpenAPI, actuator mappings, database) and *generates* a standalone k6 suite. Chosen.

## Decision
- New reactor module `spring-ai-mcp-server-common-loadtest` (package `com.springaimcpservercommon.loadtest`),
  **not** referenced by the starter, the BOM or `autoconfigure`. No Spring dependency: it reads the host,
  it never runs inside it. Dependencies: Jackson 3 (+ YAML), the PostgreSQL driver — all already vendored.
- Source discovery uses the JDK's own compiler tree API (`jdk.compiler`) instead of a parser library: no new
  dependency, and the target project does not need to compile on our classpath.
- Discovery precedence: OpenAPI (the published contract) > Java sources (constraints, entities) > actuator
  mappings (live routes, including this library's runtime-registered dynamic endpoints, LLD-04).
- **Division of ownership (one owner per fact):** Java decides *what* a field is (its semantic kind, its
  constraints, its real-data column) and writes it into the generated code; the suite's `lib/dummy.js` /
  `lib/random.js` decide *how* a value of that kind is produced at run time, so every request gets fresh
  values without re-running the generator.
- **Safety defaults:** DELETE operations disabled; identifier fields use real ids; sensitive fields (secrets,
  personal identifiers, `@AiEntityProperty`/`@AiContext` CONFIDENTIAL/RESTRICTED) are never sampled from the
  database; the database connection is read-only with statement timeouts, and only tables/columns present in
  JDBC metadata are queried; the suite refuses hosts matching `safety.blockedHostPattern` (default: names
  containing `prod`/`production`) unless `ALLOW_PROD=true`; secrets come from environment variables only;
  the library's control plane (`/dynamic-ai/admin/**`), actuator and docs routes are excluded by default.

## Consequences
- Generated suites are plain k6 projects: they run anywhere k6 runs (CI, k6 Cloud, a laptop) without Java.
- Regeneration is safe: `loadtest.config.json`, `data/user.json` and `hooks.js` keep the team's edits; API
  modules and providers are regenerated.
- Heuristics (field kinds, real-data binding) can be wrong for a given project; every decision is visible in
  `data/plan.json` and the suite README and can be overridden (`--bind`, `data/user.json`, `hooks.js`).
- Multipart/form bodies are not generated (logged and skipped). Kotlin sources are not scanned (use OpenAPI or
  actuator discovery). Both are recorded in LLD-16 §11.
- The k6 end-to-end tests run only where a `k6` binary is available (`K6_BIN` or `PATH`); otherwise they are
  skipped, while the rest of the module's tests always run.
