# springAIMcpServerCommon

Embeddable Spring Boot 4.1 starter that adds governed AI agents, dynamic endpoints/queries and an MCP server
to any Spring Java application — using the host's own identity/access management, versioning and audit flows.

- Design and decisions: [`docs/README.md`](docs/README.md)
- Host integration: [`docs/integration/host-integration-guide.md`](docs/integration/host-integration-guide.md)
- Baseline: Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL 15+ for the `dynamic_ai` store

## Build

Requires JDK 25 and Maven 3.9.6+ (a Maven wrapper will be added with `mvn -N wrapper:wrapper`).

```
mvn test              # unit tests
mvn verify            # + *IT Testcontainers integration tests (needs Docker)
scripts/build-offline.sh   # same, with no network: dependencies come from ./offline-repo
```

See [`docs/offline-build.md`](docs/offline-build.md) for the vendored dependency repository.

## Modules

| Module | Purpose |
|--------|---------|
| `spring-ai-mcp-server-common-bom` | Version alignment for hosts |
| `-annotations` | `@AiContext`, `@AiEntityProperty`, `@AiExposedAction`, `@AiParam`, `@AiQueryConstraints` |
| `-core` | Domain model, ports, catalog and policy logic |
| `-persistence` | PostgreSQL store (Flyway + isolated JPA unit): config, versioning, AI/MCP telemetry, proposals, audit |
| `-security` | Principal mapping on host Spring Security, authorization, API keys |
| `-query` | Safe dynamic queries (AST → JPA Criteria) |
| `-ai` | Agent runtime, tool bridge, write guard |
| `-mcp` | MCP server (Streamable HTTP, OAuth 2.1 resource) |
| `-webmvc` | Dynamic endpoints, admin API, SSE streaming |
| `-autoconfigure` | Spring Boot auto-configuration |
| `-spring-boot-starter` | The one dependency hosts add |
| `-loadtest` | Developer tool (not in the starter): generates Grafana k6 load tests for any Spring Boot project |
| `-jfr-analyzer` | Developer tool, not shipped to hosts: JFR recording → HTML + JSON hot-spot report (`scripts/jfr-analyze.sh`, [docs](docs/tools/jfr-analyzer.md)) |

## Load testing any Spring Boot project (k6)

`spring-ai-mcp-server-common-loadtest` discovers a Spring project's REST APIs (every controller style,
functional routes, Spring Data REST, OpenAPI, actuator mappings), builds payloads from the entity/table
relationships (JPA, database foreign keys, or Flyway/`schema.sql` DDL), seeds test data through the
application's own create endpoints parents-first, generates a k6 suite with a data provider per API and per
request DTO — dummy, random, real (sampled from and checked against the project's database, created by
seeding, or harvested from the running API) and user-supplied values — and runs it in smoke, load, stress, spike, soak or breakpoint mode, per API or as a
weighted mix of all APIs (`mixed-spike`, `mixed-stress`, …), or as replays of a browser recording exported
from DevTools' Network tab (`journey-spike`, …), with ids correlated from call to call.

```
scripts/loadtest.sh generate --project ../my-service              # writes ../my-service/load-tests
scripts/loadtest.sh generate --project ../my-service --har checkout.har   # + a recorded browser flow (DevTools ▸ Export HAR)
cd ../my-service/load-tests && ./run.sh smoke                      # then: ./run.sh mixed-spike mixed
```

Design: [`docs/lld/16-load-test-generator.md`](docs/lld/16-load-test-generator.md), [ADR-0022](docs/adr/0022-k6-load-test-generator-module.md).
