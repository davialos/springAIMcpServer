# Host Integration Guide — springAIMcpServerCommon

> **Version scope.** This guide describes the configuration contract as implemented in
> **`0.1.0-SNAPSHOT`** (`groupId com.springaimcpservercommon`, provisional per OQ-02b). Every property name,
> default, and annotation shown is either taken verbatim from the property contract in
> `docs/lld/15-database-schema.md` §12 / `CLAUDE.md`, or from the six shipped Flyway migrations that already
> create the tables involved. Sections that describe a capability whose LLD marks it **v1.x** (MCP server and
> MCP client, LLD-07 §5–6) are called out explicitly — the database tables for them already exist
> (`dai_mcp_client`, `dai_mcp_session`, `dai_mcp_request`) and the property namespace is reserved, but confirm
> against your build's release notes whether the feature is switched on before relying on it in production.

## 1. Prerequisites

- Host application on **Spring Boot 4.1.x** (Spring Framework 7), built with **Java 25**
  (`maven.compiler.release=25` or the Gradle Java 25 toolchain).
- The host's build compiles with `-parameters` (Spring Boot's Maven and Gradle plugins enable this by
  default) — the metadata scanner (LLD-02 §3.2) needs real parameter names to build tool schemas for
  `@AiExposedAction` methods that don't declare `@AiParam(name = ...)` explicitly.
- The host already has **Spring Security 7.1.x** configured against its own identity provider — this
  framework never runs a login page or issues its own sessions (SEC-01 §1); it only consumes the host's
  `Authentication`.
- A reachable **PostgreSQL 15+** instance (16/17 recommended) for the `dynamic_ai` store — either a dedicated
  database/server or the host's own database with its own schema (§3).
- Network egress from the host application to whichever LLM provider(s) it will use (out of scope for this
  guide — see `docs/lld/06-agent-runtime.md`).

## 2. Add the BOM and starter

### Maven

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.springaimcpservercommon</groupId>
      <artifactId>spring-ai-mcp-server-common-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.springaimcpservercommon</groupId>
    <artifactId>spring-ai-mcp-server-common-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation platform('com.springaimcpservercommon:spring-ai-mcp-server-common-bom:0.1.0-SNAPSHOT')
    implementation 'com.springaimcpservercommon:spring-ai-mcp-server-common-spring-boot-starter'
}
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation(platform("com.springaimcpservercommon:spring-ai-mcp-server-common-bom:0.1.0-SNAPSHOT"))
    implementation("com.springaimcpservercommon:spring-ai-mcp-server-common-spring-boot-starter")
}
```

The starter pulls in `autoconfigure` and its optional dependencies only; it never forces a dependency the host
doesn't already need for the features it enables (CLAUDE.md dependency conventions, ADR-0012). If your domain
model lives in a module that should **not** pull in Spring/JPA (e.g. a shared API jar), depend on
`spring-ai-mcp-server-common-annotations` alone there — it has zero Spring dependencies (LLD-02 §2).

## 3. Provisioning PostgreSQL

Both options are supported (ADR-0019, OQ-30); the scripts and property wiring differ only in where the store's
database lives. Full script reference: `scripts/db/postgresql/README.md`; full schema reference:
`docs/lld/15-database-schema.md`.

### Option A — dedicated database/server (recommended for PROD)

Isolates the store's write and backup volume from the host's own OLTP traffic (LLD-15 §14). Run once per
cluster/database, ahead of the first deployment:

```bash
psql -h db.internal -U postgres -d postgres \
     -v dai_migrator_password="$DAI_MIGRATOR_PASSWORD" -v dai_app_password="$DAI_APP_PASSWORD" \
     -v use_dedicated_database=true -v dai_database=dai_store \
     -f scripts/db/postgresql/01_create_roles_and_database.sql

psql -h db.internal -U postgres -d dai_store \
     -f scripts/db/postgresql/02_create_schema_and_privileges.sql
```

Then either let the application run its own migration at startup, or have a DBA run Flyway directly
(`scripts/db/postgresql/README.md` "Two operating modes") before the first deployment.

### Option B — host's own database, own schema

Simpler for DEV/TEST and for hosts without the operational appetite for a second database:

```bash
psql -h db.internal -U postgres -d orders_prod \
     -v dai_migrator_password="$DAI_MIGRATOR_PASSWORD" -v dai_app_password="$DAI_APP_PASSWORD" \
     -v use_dedicated_database=false \
     -f scripts/db/postgresql/01_create_roles_and_database.sql

psql -h db.internal -U postgres -d orders_prod \
     -f scripts/db/postgresql/02_create_schema_and_privileges.sql
```

The framework never touches the host's own tables or schema either way — it only ever creates and uses
objects inside `dynamic_ai` (CLAUDE.md namespace rules, ADR-0019).

## 4. `application.yml` examples

### DEV — reuse the host's DataSource, app-run migrations

```yaml
dynamic:
  ai:
    agent:
      enabled: true
      environment:
        tier: dev                     # explicit — never leave this unset, even in DEV (LLD-12 §2.1)
      store:
        enabled: true
        migrate: true                 # app runs Flyway itself on startup
        # datasource.url left unset ⇒ reuses the host's own DataSource, schema dynamic_ai
      endpoints:
        enabled: true
      agents:
        enabled: true
      admin:
        enabled: true
        ui:
          enabled: true
      mcp:
        server:
          enabled: true               # LLD-07 §5 — confirm this is enabled in your build (see version note above)
```

### PROD — dedicated datasource, DBA-run migrations, tuned retention

```yaml
dynamic:
  ai:
    agent:
      enabled: true
      environment:
        tier: prod
        id: orders-api-prod-eu        # ${spring.application.name}-<tier> is the default; override for clarity
      store:
        enabled: true
        migrate: false                # a DBA already ran Flyway (scripts/db/postgresql/README.md Mode B)
        validate-schema: true         # fail fast if the applied schema doesn't match what this build expects
        schema: dynamic_ai
        datasource:
          url: jdbc:postgresql://dai-db.internal:5432/dai_store
          username: dai_app
          password: ${DAI_APP_PASSWORD}
          maximum-pool-size: 20
          connection-timeout: 5s
        retention:
          telemetry-months: 13
          audit-months: 14
          conversation-days: 30
          proposal-days: 7
        maintenance:
          enabled: true               # false = you run partitions, retention and node bookkeeping yourself
          cron: "0 17 3 * * *"       # UTC
      audit:
        evidence:
          enabled: false              # opt in per workspace/regulatory need (ADR-0018, OQ-27) — off by default
      security:
        api-key:
          pepper-secret-ref: "vault:secret/dai/api-key-pepper#value"   # resolved by your SecretResolver SPI
      endpoints:
        enabled: true
      agents:
        enabled: true
      admin:
        enabled: true
        ui:
          enabled: false              # LLD-12 §2.2 — authoring/introspection UI is locked in PROD by default
      mcp:
        server:
          enabled: true
```

`store.migration.username`/`.password` are only needed if the migration identity (`dai_migrator`) differs from
the runtime identity (`dai_app`) **and** `store.migrate=true` (app-run mode) — in the PROD example above,
`store.migrate=false` means Flyway never runs from inside the application at all, so those two properties are
omitted.

## 5. Security integration

The framework never authenticates anyone itself (SEC-01 §1); it reads the host's `Authentication` and maps it
to a `DaiPrincipal` and a set of framework roles via `dai_role_mapping` rows and/or the static config below
(SEC-01 §3). Configure `dynamic.ai.agent.security.identity.*` to tell the mapper where to find the subject id,
groups/roles and (optionally) ABAC attributes in **your** IdP's tokens, then add `role-mappings` entries.

### Entra ID (Azure AD)

```yaml
dynamic:
  ai:
    agent:
      security:
        identity:
          subject-claim: oid          # stable object id — do NOT use email or upn as the subject
          groups-claim: roles         # prefer App Roles surfaced as "roles" over the raw "groups" claim
        role-mappings:
          - match: { authority: "APPROLE_PlatformAdmin" }
            role: PLATFORM_ADMIN
          - match: { group: "AppRole.SalesEngineering.Owner" }
            role: WORKSPACE_OWNER
            workspace: sales
```

Assign **App Roles** to Entra security groups rather than reading the raw `groups` claim directly: a user in
more than ~200 groups gets a *group overage* claim (`_claim_names`/`hasgroups`) instead of the actual list, and
resolving it needs an extra Microsoft Graph call via a custom `GroupResolver` (SEC-01 §2). App Roles avoid the
overage case entirely and are the pattern SEC-01 recommends.

### Okta

```yaml
dynamic:
  ai:
    agent:
      security:
        identity:
          subject-claim: sub
          groups-claim: groups
        role-mappings:
          - match: { group: "sales-analysts" }
            role: AUTHOR
            workspace: sales
          - match: { group: "sales-approvers" }
            role: APPROVER
            workspace: sales
```

### Keycloak

```yaml
dynamic:
  ai:
    agent:
      security:
        identity:
          subject-claim: sub
          groups-claim: realm_access.roles
        role-mappings:
          - match: { group: "platform-admin" }
            role: PLATFORM_ADMIN
```

Keycloak's realm roles arrive nested (`realm_access.roles`, not a top-level claim). Confirm your
`AuthorityMapper`/`PrincipalAttributeResolver` SPI implementation (SEC-01 §3) actually resolves a dotted claim
path before relying on this in PROD — if the default mapper only reads top-level claim names, provide a small
custom `PrincipalAttributeResolver` bean that flattens `realm_access.roles` into a claim the default mapper can
read, or map Keycloak client roles into a top-level custom claim in your Keycloak client mapper configuration
instead.

### LDAP / Active Directory

```yaml
dynamic:
  ai:
    agent:
      security:
        role-mappings:
          - match: { ldapGroup: "cn=data-owners,ou=groups,dc=acme,dc=com" }
            role: APPROVER
          - match: { ldapGroup: "cn=sales-team,ou=groups,dc=acme,dc=com" }
            role: AUTHOR
            workspace: sales
```

`memberOf` DNs resolve to `GrantedAuthority`s through Spring Security's LDAP support upstream of this mapping;
`ldapGroup` matches the full DN, not just the CN, to avoid collisions across OUs.

Whichever IdP you use, mapping results are cached per `(issuer, subject, token hash/session id)` for up to 5
minutes (SEC-01 §3) — a role mapping change takes effect for a given user within that window, not instantly.

## 6. Annotating host code

Only annotated elements are ever visible to AI or the dynamic query engine (LLD-02 §1 — opt-in, not
opt-out). A realistic `Order`/`Customer` example, including a write that becomes a reviewed proposal instead
of a direct mutation:

```java
package com.acme.orders.domain;

import com.springaimcpservercommon.annotations.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@AiContext(
    description = "A customer's purchase order and its current status.",
    keywords = {"order", "purchase", "sales order"})
@AiQueryConstraints(maxLimit = 50, mandatoryFilters = {"customerId"})   // every AI/dynamic query on Order must filter by customerId
public class Order {

    @Id
    private UUID id;

    @AiEntityProperty(meaning = "The customer who placed this order.")
    // writable defaults to false: exposed for reads, never as an editable field in a proposal
    private UUID customerId;

    @AiEntityProperty(meaning = "Current lifecycle status of the order.", writable = true)
    private OrderStatus status;

    @AiEntityProperty(meaning = "Total order amount, in the order's currency.")
    private BigDecimal totalAmount;

    @AiEntityProperty(meaning = "Free-text delivery instructions from the customer.", writable = true)
    private String deliveryNotes;

    // `internalRiskScore` deliberately carries NO @AiEntityProperty: it stays invisible to AI and the
    // dynamic query engine by default (LLD-02 §4 "unannotated attributes of an annotated entity are not
    // exposed"), even though the entity itself is annotated.
    private int internalRiskScore;

    // getters/setters/business methods omitted
}
```

```java
package com.acme.orders.service;

import com.springaimcpservercommon.annotations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@AiContext(description = "Operations on customer orders: lookup and status changes.")
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @AiExposedAction(
        intent = "Find recent orders for a customer, optionally filtered by status.",
        readOnly = true)
    public List<Order> findRecentOrders(
            @AiParam(description = "Customer id to search orders for.") UUID customerId,
            @AiParam(description = "Order status to filter by.", required = false) OrderStatus status,
            @AiParam(description = "Maximum number of orders to return (server-capped at 50).") int limit) {
        return orderRepository.findRecent(customerId, status, Math.min(limit, 50));
    }

    @AiExposedAction(
        intent = "Cancel an order that has not yet shipped.",
        readOnly = false,             // ⇒ this is NEVER invoked directly by a model — see note below
        idempotent = true)
    @Transactional
    public void cancelOrder(
            @AiParam(description = "Id of the order to cancel.") UUID orderId,
            @AiParam(description = "Reason the order is being cancelled.") String reason) {
        orderRepository.findById(orderId).orElseThrow().cancel(reason);
    }
}
```

Because `cancelOrder` declares `readOnly = false`, `SecuredToolCallback` never calls this method body on the
model's behalf (LLD-07 §3 step 5, ADR-0009). Instead, a model-initiated (or MCP-initiated) call to this tool:

1. Is intercepted before the delegate runs.
2. Builds a `dai_change_proposal` row (`target_kind = 'HOST_OPERATION'`, `target_ref = 'op:com.acme.orders.
   service.OrderService#cancelOrder(...)'`) with a before-snapshot and the supplied arguments.
3. Returns the tool-result envelope `{"status": "proposed", "proposalId": "..."}` to the model — the model
   sees that a proposal was created, never that the order was cancelled.
4. `cancelOrder(...)` only actually runs later, when the **human proposal owner** confirms it through the
   review UI (or, for a bulk/RESTRICTED-entity change per SEC-01 §6, after a second approver also confirms) —
   at that point it runs as an ordinary authenticated call, through the same Spring proxy, with the confirming
   user's own `Authentication` (LLD-07 §4, runs-as-caller).

`@AiParam` is optional when the host compiles with `-parameters` (§1) and the parameter name alone is a good
enough description key — but always supply `description`; it is what the LLM actually reads.

## 7. Enabling features

Every top-level feature switch defaults to **`false`** (LLD-01 §4); enable only what a given environment
needs:

```yaml
dynamic:
  ai:
    agent:
      enabled: true              # master switch — everything else is inert if this is false
      endpoints:
        enabled: true            # metadata-driven dynamic REST endpoints (data plane)
      agents:
        enabled: true            # Spring AI agent runtime, chat API
      admin:
        enabled: true            # admin REST API (control plane)
        ui:
          enabled: true          # embedded admin dashboard — see §8 for the PROD default
      mcp:
        server:
          enabled: true          # exposes agents/tools over MCP at /dynamic-ai/mcp — LLD-07 §5, confirm v1.x status
```

`dynamic.ai.agent.store.enabled` (default `true`) is independent of these — the persistence unit initializes
whenever the starter is on the classpath and `dynamic.ai.agent.enabled=true`, because audit/versioning tables
are foundational to every other feature.

## 8. Environment tiers and the PROD lock-down

`dynamic.ai.agent.environment.tier` is `DEV`, `TEST`, `STAGE`, or `PROD`; **leaving it unset resolves to
`UNKNOWN`, which is treated as `PROD`** (fail-closed, LLD-12 §2.1, OQ-19) — always set it explicitly, even in
DEV. Active Spring profiles matching `prod-profile-patterns` (default `prod,production,live,prd,*-prod`) can
only make the resolved tier *stricter*, never looser: an explicit `tier: dev` alongside an active `prod`
profile still resolves to PROD, logged as `CRITICAL` and audited as `ENVIRONMENT_CONFLICT`.

In PROD, **authoring, introspection, query preview/explain, and the playground are disabled at the bean
level** by default (LLD-12 §2.2) — there is no controller to reach, not just a permission check. Published
endpoints/agents/tools, reviewed writes, MCP, and ops views (usage, audit, kill switches) all continue to work
in PROD; only the "build/inspect the configuration live" surfaces are locked. Config changes in PROD go
through a signed bundle import promoted from STAGE, not the live UI (LLD-09 §5, LLD-12 §2.2).

A time-boxed break-glass override exists for genuine incidents:

```yaml
dynamic:
  ai:
    agent:
      environment:
        tier: prod
        production-override:
          enabled: true
          capabilities: [AUTHORING, PLAYGROUND]
          expires-at: 2026-10-01T18:00:00Z   # mandatory; rejected at startup if missing or > 72h away
          reason: "INC-4412 hotfix of agent prompt"
```
Every action taken under an active override is audited with `override=true`, and the affected capability turns
off again automatically at `expires-at` without a restart (LLD-12 §2.3).

## 9. MCP client configuration

*(LLD-07 §5, tagged v1.x — the `dai_mcp_client`/`dai_mcp_session`/`dai_mcp_request` tables already exist in
the shipped schema; confirm the feature flag's actual availability in your build.)*

The framework exposes an **MCP Streamable HTTP** endpoint at `{base-path}/dynamic-ai/mcp` on the host's own
port — never a second listener. It is an OAuth 2.1 protected resource, not an authorization server: it
publishes Protected Resource Metadata (RFC 9728) at
`/.well-known/oauth-protected-resource/dynamic-ai/mcp`, pointing at the host's own authorization server
(whichever IdP you configured in §5). Point an MCP-capable client at:

```
https://<host>/dynamic-ai/mcp
```

The client must present a bearer token whose **audience** is this MCP resource (RFC 8707) — a token minted for
a different API is rejected — and whose `client_id` has been approved for the workspace
(`dai_mcp_client.status = 'APPROVED'`, admin-configured; LLD-07 §5.3). A user's first use of a newly approved
client records a consent entry (`dai_mcp_client_consent`), visible and revocable from the dashboard. Scopes:

| Scope | Allows |
|---|---|
| `dai.mcp.read` | List tools; call `readOnly=true` tools |
| `dai.mcp.propose` | Call write tools — which only ever create a proposal (§6), never execute directly |
| `dai.mcp.agents` | Call `ask_<agent>` tools |

`dynamic.ai.agent.mcp.server.mode` (`stateful` default, or `stateless` for multi-replica hosts without sticky
sessions — OQ-22) controls whether the server keeps per-session state or treats every request independently.

### Conversation history (opt-in)

Users can list, read, close and erase their own conversations through `/dynamic-ai/api/conversations`, but
transcripts contain what users typed, so nothing is stored until you enable it:

```yaml
dynamic.ai.agent.conversations:
  enabled: true          # default false
  retention: 30d         # kept this long after the last activity; 1ms..3660d
  max-stored-chars: 100000
  purge-interval: 15m    # expired conversations are deleted (runs even while enabled=false)
```

Each successful turn stores the user message and the answer, after redaction: a message that contains a
credential (private key, API or cloud token, JWT, URL with credentials, password assignment) is replaced by a
placeholder, never stored in part. Refused, failed and cancelled turns store nothing. PII detectors are not
implemented yet. The stored conversation uses the conversation id the client sends and is found again through a
hash of (principal, agent, conversation id), so another user cannot read or append to it. A conversation the user
erased or closed is not written to again. This is the history shown to users, not the memory the model reads;
that is still in-memory per node.

## 10. Operations

- **Health**: a `HealthContributor` named `dynamicAi` reports catalog state, snapshot generation/lag, config
  store reachability, and model-provider breaker states. It does **not** join the host's readiness group by
  default (a degraded AI feature must not take the host's `/actuator/health/readiness` down) — opt in only if
  your deployment explicitly wants that coupling.
- **Metrics**: Micrometer, prefix `dynamic.ai.agent.*`, registered into the host's own `MeterRegistry` (the
  library never starts its own metrics server). Key meters: `.endpoint.requests`, `.query.executions`,
  `.agent.turns`, `.agent.tool.calls`, `.llm.tokens`, `.llm.cost`, `.budget.utilization`,
  `.ratelimit.rejections`, `.snapshot.generation`/`.snapshot.lag`, `.authz.decisions` (LLD-10 §2). Spans:
  `dai.endpoint`, `dai.query`, `dai.agent.turn`, `dai.tool`, `dai.snapshot.apply` (LLD-10 §3).
- **Partition maintenance**: runs on `dynamic.ai.agent.store.maintenance.cron` (default `0 17 3 * * *`, UTC; disable
  with `store.maintenance.enabled=false`),
  advisory-locked so exactly one cluster node does the work per run (`dai_job_run` records the outcome). Verify
  it is actually running with `scripts/db/postgresql/04_verify.sql` §3–4 (empty `DEFAULT` partitions, expected
  partitions present).
- **Retention**: `dynamic.ai.agent.store.retention.{telemetry-months,audit-months,conversation-days,
  proposal-days}` (§4 PROD example) — see `docs/lld/15-database-schema.md` §10 for exactly how each is
  enforced (partition drop vs. row purge).
- **Backups**: continuous archiving (WAL + base backups) is recommended over `pg_dump` alone for anything
  beyond a dev snapshot; see `docs/lld/15-database-schema.md` §14 for the full restore-drill checklist,
  including why roles/grants must be recreated (`scripts/db/postgresql/02_`/`03_`) before an application can
  reconnect to a restored store.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App fails to start with a Flyway `permission denied for schema dynamic_ai` | Migration credentials point at `dai_app` (DML-only) instead of `dai_migrator` | Set `dynamic.ai.agent.store.migration.username/.password` to `dai_migrator`, or switch to DBA-run migrations (`store.migrate=false`) |
| Runtime queries fail with `permission denied for table dai_...` | `02_create_schema_and_privileges.sql` never ran, or ran *after* Flyway already created the tables | Run `scripts/db/postgresql/03_post_migration_grants.sql` as a catch-up |
| Authoring/introspection/playground unexpectedly disabled in what you believe is DEV | `dynamic.ai.agent.environment.tier` unset ⇒ resolves to `UNKNOWN` ⇒ treated as `PROD` (§8) | Set `environment.tier: dev` explicitly |
| Startup logs `ENVIRONMENT_CONFLICT` / `CONFIG_STORE_IDENTITY_MISMATCH` | An active Spring profile matches a prod pattern despite an explicit non-prod tier, or this app is pointed at a store whose `dai_environment` row belongs to a different environment (e.g. a prod backup restored into stage) | Fix the profile/tier mismatch, or confirm you are connecting to the intended store (`scripts/db/postgresql/04_verify.sql` §2) |
| A tool call always returns `status: not_permitted` even though the method is annotated | Default-deny (SEC-01 §1): annotation alone only makes something *eligible*; the caller also needs a `dai_grant` | Grant the permission via the admin API/UI, or a bootstrap `role-mappings` entry that reaches a role with that permission (SEC-01 §5) |
| An `@AiExposedAction` method never appears as a tool, with a `ScanIssue` about parameter names | Host compiled without `-parameters` and no `@AiParam(name=...)` given | Enable `-parameters` (default for Spring Boot's Maven/Gradle plugins) or add explicit `name` to every `@AiParam` |
| A list-returning action is excluded with `UNBOUNDED_LIST_ACTION` | No `Pageable`/`Limit` parameter, no paged return type, and no `@AiParam` acting as a limit | Add a limit parameter or paginated return type (LLD-02 §4) |
| MCP client gets `401` with `WWW-Authenticate: Bearer resource_metadata="..."` | No token, wrong audience, or the client isn't in `dai_mcp_client` as `APPROVED` yet | Check the token's `aud` claim against the MCP resource URI; have an admin approve the client (§9) |
| Rows keep landing in `dai_agent_turn_pdefault` (or the other `_pdefault` partitions) | The partition-maintenance job is disabled, failing, or badly behind schedule | `scripts/db/postgresql/04_verify.sql` §3–4; check `dai_job_run` for recent failures; confirm `maintenance.cron` is actually configured and the job isn't losing the advisory lock race indefinitely |
| A confirmed write proposal never applies, stuck in `APPLYING` | The node that confirmed it crashed before recording `APPLIED`/`FAILED` | This is exactly what `ix_change_proposal_applying` (LLD-15 §6.4) exists to find — the crash-reconciliation job should pick it up on its next run (LLD-11 §10); if it doesn't, that job itself needs investigating |
