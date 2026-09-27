# ADR-0019: PostgreSQL store in an isolated persistence unit (own EMF, transaction manager and Flyway — none exposed as beans)
- Status: Accepted (product owner chose PostgreSQL, 2026-09-28) · Resolves OQ-03, OQ-10

## Context
The framework stores configuration, versioning, AI/MCP telemetry, write proposals and audit. The product owner
chose PostgreSQL and asked for auto-configurable JPA entities. The store must never disturb the host's own JPA setup:
Spring Boot's auto-configuration backs off when it finds any `EntityManagerFactory`, `LocalContainerEntityManagerFactoryBean`,
`TransactionManager` or `Flyway` bean, and by-type injection of those types in host code breaks when a second bean exists.

## Options considered
1. Add our entities to the host's persistence unit — pollutes the host schema management (`ddl-auto`), forces PostgreSQL on the host DB.
2. Register a second EMF/TM/Flyway as beans (`@Bean(defaultCandidate=false)`) — still trips `@ConditionalOnMissingBean` checks depending on auto-configuration order.
3. **Build our EMF, `JpaTransactionManager` and Flyway instance programmatically inside one library bean (`DaiPersistenceUnit`), never registering them as beans.**
   DataSource: our own pooled `DataSource` when `dynamic.ai.agent.store.datasource.url` is set (dedicated PostgreSQL), otherwise the host's unique `DataSource`, always with schema `dynamic_ai`.

## Decision
Option 3. Our code uses the unit's `TransactionTemplate` and shared `EntityManager` explicitly; nothing is injectable by the host's
`@Transactional` or repositories. Schema is owned by our Flyway migrations (`classpath:db/dynamic-ai/migration`, history table
`dai_schema_history`); Hibernate runs with `hbm2ddl.auto=none` (tests use `validate`). No Spring Data repositories (their
auto-configuration would interact with the host's). Provisional groupId/package `com.springaimcpservercommon` until OQ-02b.

## Consequences
+ Zero interference with host JPA, transactions, Flyway, or repositories; dedicated audit database possible.
− Hosts must provide PostgreSQL 15+ for our schema; our stores are hand-written JPA DAOs.
