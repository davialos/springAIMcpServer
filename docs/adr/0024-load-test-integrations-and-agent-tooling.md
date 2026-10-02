# ADR-0024: Load-test generator integrations — Java API, build tools, JUnit, Grafana, MCP and agent skills
- Status: Accepted (2026-10-02)
- Deciders: product owner (request: "full-fledged Spring + Grafana load-test configuration generator, Java library
  and agent skill set"), implementation
- Builds on: ADR-0022 (generator as a developer-tool module), LLD-16

## Context
ADR-0022 delivered the generator as a CLI (`scripts/loadtest.sh`). Spring teams consume tools through their build
(Maven/Gradle), their tests (JUnit 5, `@SpringBootTest`), their dashboards (Grafana) and, increasingly, coding
agents. Each of these needs the generator as a library, and each brings constraints: the generator is Java 25,
`offline-repo/` must vendor every build dependency, Gradle daemons commonly run on older JDKs, and an MCP server
writes files and sends traffic on an agent's behalf.

## Decision
1. **One public API, everything else is a layer.** `com.springaimcpservercommon.loadtest.api`:
   `LoadTestGenerator` (immutable, builder mirroring every CLI option; `discover()`, `generate()`),
   `LoadTestRunner` (k6 process, output streaming, timeout, Grafana output, `testid` tag), `LoadTestReport`,
   `ReportComparison` (regression gate). The CLI, Maven plugin, Gradle script, JUnit extension and MCP server use
   only these types.
2. **Maven plugin** `spring-ai-mcp-server-common-loadtest-maven-plugin` (prefix `loadtest`: `discover`,
   `generate`, `run`, `compare`). It runs in Maven's JVM, so Maven must run on JDK 25 — the same requirement
   this repository's build already has; forking a JVM per goal was rejected as needless complexity for a tool
   whose users build on JDK 25. Vendored: `maven-plugin-api` 3.9.11 and `maven-plugin-annotations` (provided),
   `maven-plugin-plugin` 3.15.2 (ASM 9.9 reads Java 25 classes).
3. **Gradle via a script plugin, not a binary plugin.** `gradle/loadtest.gradle` (written by
   `loadtest init-gradle`) registers `JavaExec` tasks that fork the generator on a **Java 25 toolchain**, so they
   work whatever JDK runs Gradle. A binary plugin was rejected: compiling it needs the Gradle API jar
   (`dev.gradleplugins:gradle-api`, 185 MB) in `offline-repo/`, and a Java 25 plugin class cannot load in a daemon
   on an older JDK anyway.
4. **JUnit 5 extension** `spring-ai-mcp-server-common-loadtest-junit`: `@K6LoadTest` generates the suite once per
   test class; `K6Suite` runs it against the test's application. No Spring dependency: the port is read from a
   field annotated `@LocalServerPort` *by simple name* (or `@K6Target`). Without k6 the tests abort (skipped)
   unless `requireK6`; tests carry the tag `load-test`.
5. **Grafana stack per suite.** Every suite gets `grafana/` (Prometheus with the remote-write receiver scraping
   the app's `/actuator/prometheus`, Grafana with a provisioned datasource and dashboard). Ports bind to
   `127.0.0.1` and Grafana allows anonymous admin — a local tool, documented as such; teams own the compose and
   Prometheus files after the first generation, the dashboard is regenerated. Run annotations are best effort and
   never fail a run.
6. **MCP server** `spring-ai-mcp-server-common-loadtest-mcp` on stdio (plain MCP Java SDK 2.0, the version
   `spring-ai-mcp` uses; no Spring): tools `loadtest_discover|generate|run|report|compare|modes`. Every path is
   resolved against a root (`--root`, default the working directory) and refused outside it, symbolic links
   included; tool failures are results with `isError`, not protocol errors; runs are capped at one hour; tool
   annotations mark `loadtest_run` as non-read-only and open-world. Started by `scripts/loadtest-mcp.sh`.
7. **Agent skill set as a Claude Code plugin** in `claude-plugins/spring-loadtest/` (skills
   `loadtest-generate`, `loadtest-analyze`, `loadtest-capacity`; agent `load-test-engineer`; the MCP server), listed
   by the repository's `.claude-plugin/marketplace.json`. The skills encode the safety rules: smoke before load,
   confirm the target before sending traffic, never production, fix causes instead of loosening thresholds.
8. **REST resources without tables.** When neither JPA nor DDL nor a database describes a resource, a collection
   `POST /x` with an item route `/x/{var}` is seeded and its ids reused (found by the JUnit extension test against
   a Mongo-style app).

## Consequences
- New reactor modules are developer tools: not in the starter, not in the BOM.
- `offline-repo/` grows by the Maven plugin tooling (refreshed per docs/offline-build.md).
- The Gradle integration is a file in the user's project, updated by re-running `init-gradle --force`.
- The MCP server can send load to any URL the suite targets; the root confinement protects the file system, the
  suite's `safety.blockedHostPattern` and the skills' confirmation rule protect production.
- The plugin's MCP server needs `LOADTEST_HOME` (a checkout of this repository) until the generator is published to
  a Maven repository.
