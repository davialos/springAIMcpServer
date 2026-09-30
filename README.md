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
