# Open Questions

Status legend: **OPEN** · **DEFERRED** (parked by product owner, discuss later) · **RESOLVED** (link to decision)

Deployment criticality:
- **D-BLOCKER** — must be answered before the first production deployment of any host.
- **D-CONFIG** — has a safe default, but each deploying team must confirm or override it in configuration.
- **LATER** — does not affect a first deployment.

## A. Deployment-critical (answer before first production deployment)

| ID | Question | Current default / leaning | Owner | Blocks | Criticality | Status |
|----|----------|---------------------------|-------|--------|-------------|--------|
| OQ-02b | Maven groupId / org reverse-domain for the base package | Provisional `com.springaimcpservercommon` (ADR-0019); one mechanical rename before first release | user | release, all modules | D-BLOCKER | OPEN |
| OQ-09 | Which IdPs/auth styles do the target teams use (Entra ID, Okta, Keycloak, LDAP, SAML)? | All supported (SEC-01 §2); claim mapping must be configured per host | user | SEC-01, role mappings | D-BLOCKER | DEFERRED |
| OQ-11 | Approved LLM providers; on-prem requirement for RESTRICTED data? | Any Spring AI provider via `ModelRouter`; RESTRICTED ⇒ on-prem only | user | LLD-06, F-77 | D-BLOCKER | DEFERRED |
| OQ-13 | Distribution: internal Nexus/Artifactory or Maven Central? | — | user | release | D-BLOCKER | OPEN |
| OQ-17 | Align runtime namespaces (`dynamic.ai.agent.*`, `dai_*`, `/dynamic-ai`) with product name before first release? | Keep current; renaming after 1.0 is breaking | user | ADR-0010 | D-BLOCKER | OPEN |
| OQ-19 | Unknown environment tier: treat as PROD or refuse to start until `environment.tier` is set? | Treat as PROD + clear log | user | LLD-12 §2.1 | D-CONFIG | OPEN |
| OQ-22 | MCP default mode: stateful Streamable HTTP (sticky sessions) or STATELESS? | Stateful; stateless for multi-replica hosts without sticky sessions | user | LLD-07 §5.1 | D-CONFIG | OPEN |
| OQ-27 | Which workspaces/regulations need audit evidence mode (GDPR, HIPAA, PCI, SOX)? | Off; opt-in per workspace | user | ADR-0018 | D-CONFIG | DEFERRED |
| OQ-28 | Propagate `traceparent` to external LLM providers? | Off (internal endpoints on) | access-management-architect | LLD-10 §3 | D-CONFIG | OPEN |
| OQ-29 | Minimum PostgreSQL version? | **15** (needs `UNIQUE NULLS NOT DISTINCT`); 16/17 recommended | user | LLD-15 | D-BLOCKER | OPEN |
| OQ-30 | Dedicated PostgreSQL database/server for the `dynamic_ai` schema, or the host's database? | Both supported; dedicated recommended for PROD (isolation of audit volume) | user | ADR-0019, LLD-15 | D-CONFIG | OPEN |
| OQ-31 | Audit/telemetry retention per environment (default 13 months telemetry, 400 days audit) and who runs partition maintenance | Built-in scheduled job with advisory lock (any node) | user | LLD-15 §6 | D-CONFIG | OPEN |

## B. Product / design questions (not blocking a first deployment)

| ID | Question | Current default / leaning | Owner | Blocks | Criticality | Status |
|----|----------|---------------------------|-------|--------|-------------|--------|
| OQ-01 | Support WebFlux hosts? | v1 servlet-only | lld-chief-architect | LLD-04 | LATER | OPEN |
| OQ-05 | `mcp-security` community project vs own resource-server config | Own Spring Security config | agent-runtime-designer | LLD-07 | LATER | OPEN |
| OQ-06 | Spring Data repository methods as actions? | Allowed with `@AiExposedAction`, read-only | metadata-extraction-designer | LLD-02 | LATER | OPEN |
| OQ-07 | Shared rate-limit/budget backend | PostgreSQL-backed (we already require it) | dynamic-runtime-designer | LLD-04/10 | LATER | OPEN |
| OQ-08 | Admin UI framework | React+Vite prebuilt into JAR | control-plane-designer | LLD-08 | LATER | OPEN |
| OQ-14 | Host versioning mechanisms inventory (Envers, history tables, triggers, temporal) | All supported via `VersioningAdapter` | user | LLD-11 | LATER | DEFERRED |
| OQ-15 | Web Components vs React library for review/display components | Web Components | control-plane-designer | LLD-11 §7 | LATER | OPEN |
| OQ-16 | ENTITY_WRITE in v1 or HOST_OPERATION only? | HOST_OPERATION only | user | LLD-11 §4 | LATER | OPEN |
| OQ-18 | Step-up authentication for confirming sensitive writes? | Configurable, default off | access-management-architect | LLD-11 §6 | LATER | OPEN |
| OQ-20 | Unannotated attributes of `@AiContext` entities hidden? | Hidden | user | LLD-02 §4 | LATER | OPEN |
| OQ-21 | Hibernate write-veto `Integrator` default on? | On | lld-chief-architect | ADR-0014 | LATER | OPEN |
| OQ-23 | Stream resumption in v1? | v1.x | user | LLD-13 §7 | LATER | OPEN |
| OQ-24 | stdio→HTTP bridge CLI | v1.x if needed | user | ADR-0016 | LATER | OPEN |
| OQ-25 | Default `tools.max-parallel-per-turn` | 4 | lld-chief-architect | LLD-14 §5 | LATER | OPEN |
| OQ-26 | `UNBOUNDED_LIST_ACTION` exclude or warn | Warn; exclude in strict | user | LLD-14 §3.3 | LATER | OPEN |

## C. Resolved

| ID | Question | Decision |
|----|----------|----------|
| OQ-02 | Product name, artifactIds | springAIMcpServerCommon, `spring-ai-mcp-server-common-*` → ADR-0010 |
| OQ-03 | Config store technology | JPA entities in an **isolated persistence unit** (own EMF/TM/Flyway, not beans) → ADR-0019 |
| OQ-04 | Kotlin hosts | Runtime annotations work unchanged → ADR-0013 |
| OQ-10 | Which DB for configuration, audit and versioning? | **PostgreSQL** (product owner, 2026-09-28) → ADR-0019, LLD-15 |
| OQ-12 | Write capability in v1 | Reviewed change proposals only → ADR-0009, LLD-11 |
