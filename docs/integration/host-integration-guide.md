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

Then either let the application run its own migration at startup (`dynamic.ai.agent.store.migrate=true`, the
default), or have a DBA run Flyway directly (`scripts/db/postgresql/README.md` "Two operating modes") before the
first deployment and set `store.migrate=false`.

By default the store uses the host's own `DataSource` (the one Spring Boot builds from `spring.datasource.*`). To
put it on the dedicated database, declare the persistence unit yourself; the auto-configuration then backs off:

```java
@Bean(destroyMethod = "close")
DaiPersistenceUnit daiPersistenceUnit(@Qualifier("daiDataSource") DataSource daiDataSource) {
    return DaiPersistenceUnit.start(daiDataSource,
            DaiPersistenceSettings.defaults("orders-api-prod-eu", "PROD"));   // environment id, tier
}
```

`daiDataSource` is an ordinary pooled `DataSource` for `jdbc:postgresql://dai-db.internal:5432/dai_store` as the
`dai_app` user. Mark your own primary `DataSource` `@Primary` so the rest of the host keeps using it.

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

Every property below exists in `DaiProperties` (`dynamic.ai.agent.*`); Spring ignores unknown keys silently, so
copy names exactly. The whole library is on as soon as the starter is on the classpath
(`dynamic.ai.agent.enabled`, default `true`); only the MCP endpoint and conversation recording are opt-in.

### DEV — host's DataSource, app-run migrations

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: orders
    password: ${DB_PASSWORD}

dynamic:
  ai:
    agent:
      environment:
        tier: DEV                     # explicit — never leave this unset (UNKNOWN is treated as PROD, LLD-12 §2.1)
        application-name: orders-api
      store:
        migrate: true                 # default: Flyway runs at startup into schema dynamic_ai
      mcp:
        enabled: true                 # serves POST /dynamic-ai/mcp (default false) — §9
```

### PROD — DBA-run migrations, schema validation, explicit identity

```yaml
dynamic:
  ai:
    agent:
      environment:
        tier: PROD
        id: orders-api-prod-eu        # default: <application-name>-<tier>
      store:
        migrate: false                # a DBA already ran Flyway (scripts/db/postgresql/README.md Mode B)
        validate-schema: true         # fail fast if the applied schema does not match this build
        maintenance:
          enabled: true               # false = you run partitions, retention and node bookkeeping yourself
          cron: "0 17 3 * * *"        # UTC
      security:
        groups-claim: groups
        cache-ttl: 2m                 # how long a node may serve a user's old roles after a change elsewhere
      conversations:
        enabled: true                 # record transcripts (redacted) — off by default
        retention: 30d
        erase-mode: RETAIN_FOR_AUDIT  # default; HARD deletes on user erase — see §9 "Conversation history"
        audit-retention: 90d
      memory:
        retention: 24h                # what the model remembers of a conversation
      model:
        failure-threshold: 5          # a provider failing 5 calls in a row is skipped for breaker-open-for
        breaker-open-for: 30s
      mcp:
        enabled: true
        resource-uri: https://orders.example.com/dynamic-ai/mcp
        authorization-servers: [ "https://login.example.com" ]
```

Reference of the top-level groups (defaults in parentheses):

| Group | Keys |
|---|---|
| `environment` | `tier` (`UNKNOWN` ⇒ treated as PROD), `id`, `application-name`, `prod-profile-patterns` |
| `store` | `migrate` (true), `validate-schema` (false), `maintenance.*` (enabled, `cron`, `snapshot-poll-interval` 5s, `heartbeat-interval`, `sweep-interval`, `node-retention`, `approval-ttl`, `apply-timeout`, `node-id`) |
| `security` | `groups-claim` (groups), `clearance-claim`, `default-clearance` (INTERNAL), `attribute-claims`, `issuer-override`, `local-issuer`, `cache-ttl` (5m, max 5m), `cache-max-entries`, `static-role-mappings`, `filter-chains.enabled` (true) |
| `mcp` | `enabled` (false), `workspace-id`, `transport` (STATELESS), `allowed-origins`, `resource-uri`, `authorization-servers`, `max-request-bytes`, `require-approved-client` (true) |
| `conversations` | `enabled` (false), `retention` (30d), `max-stored-chars`, `purge-interval`, `erase-mode` (RETAIN_FOR_AUDIT), `audit-retention` (90d) |
| `memory` | `persistent` (true), `retention` (24h), `max-stored-chars` |
| `model` | `failure-threshold` (5), `breaker-open-for` (30s) |
| `budget` | `enforce` (true) and limits — §10 |
| `write` | `enabled` (false) — reviewed change proposals, §6 |
| `review` | `required-approvals` (1) |
| `chat`, `query`, `scan` | endpoint limits, query bulkhead (`max-concurrency`, `timeout`), `@Ai*` scan (`base-packages`, `strict`) |

## 5. Security integration

The framework never authenticates anyone itself (SEC-01 §1). It reads the host's `Authentication` and maps it to
a `DaiPrincipal` with framework roles, from `dai_role_mapping` rows (admin API) and/or the static mappings below
(SEC-01 §3).

### Filter chains

The starter registers three Spring Security chains, each limited to its own paths, so your chains keep everything
else:

| Chain | Paths | Accepts | Notes |
|---|---|---|---|
| admin | `/dynamic-ai/admin/**` | your session login, bearer tokens | CSRF for session requests, exempt for requests with credentials in a header |
| mcp | `/dynamic-ai/mcp` | bearer tokens | stateless; 401 carries the RFC 9728 challenge when `mcp.resource-uri` is set |
| api | every other `/dynamic-ai/**` | bearer tokens | stateless, no CSRF |

Bearer tokens are validated with **your** `JwtDecoder` (or `OpaqueTokenIntrospector`) bean and converted with your
`JwtAuthenticationConverter` if you have one, so claims and authorities look exactly as in the rest of your app.
If you rely on Spring Boot's default chain, it stays in place for the rest of the app. To assemble the chains
yourself, set `dynamic.ai.agent.security.filter-chains.enabled=false` and use `DynamicAiHttpSecurityConfigurer`.
API keys for service accounts are not wired yet (OQ-37).

### Roles, memberships and grants

Access is default-deny. A caller needs, in order:

1. **A global role** from a role mapping (e.g. `PLATFORM_ADMIN` to create workspaces), or
2. **A workspace role** (`WORKSPACE_OWNER`, `AUTHOR`, `APPROVER`, `OPERATOR`, `CONSUMER`) granted with
   `POST /dynamic-ai/admin/api/v1/workspaces/{id}/members`, and
3. **A grant** for the grant-only permissions (`agent:invoke`, `endpoint:invoke`, `tool:invoke`, …) with
   `POST /dynamic-ai/admin/api/v1/workspaces/{id}/grants`. `CONSUMER` alone carries no permission.

Platform admins run the platform but do not author: authoring is `AUTHOR`, approving is `APPROVER` (and nobody
approves their own revision). A typical agent rollout is: create workspace → add an author and an approver →
author creates the agent draft and submits it → approver approves → publish → grant `agent:invoke` to its users.
State transitions use optimistic concurrency: send the `rowVersion` you last read as `If-Match`.

Changes made through the admin API take effect immediately on the node that served the request. Other nodes pick
them up within `security.cache-ttl` (roles), 5 seconds (grants) and `store.maintenance.snapshot-poll-interval`
(published generations).

### Static role mappings

```yaml
dynamic:
  ai:
    agent:
      security:
        groups-claim: groups          # the claim holding group names (default "groups")
        static-role-mappings:
          - source: AUTHORITY         # AUTHORITY | SCOPE | LDAP_GROUP | OIDC_CLAIM
            match-value: "SCOPE_dai.admin"
            role: PLATFORM_ADMIN
          - source: OIDC_CLAIM
            claim-name: roles
            match-value: "Sales.Author"
            role: AUTHOR
            workspace-id: 0193c2f4-…  # workspace-scoped roles take the workspace id (not its slug)
```

`match-value` is a glob. The subject is the token's `sub` (never an e-mail or UPN); the subject and roles claims
are not configurable today.

**Entra ID:** map **App Roles** (the `roles` claim) with `source: OIDC_CLAIM, claim-name: roles` rather than the raw
`groups` claim: a user in more than ~200 groups gets a *group overage* marker instead of the list, and resolving it
needs a custom `GroupResolver` bean (SEC-01 §2).

**Keycloak:** realm roles arrive nested (`realm_access.roles`). Add a client mapper that emits them as a top-level
claim, or provide a `PrincipalAttributeResolver` bean that flattens them.

**LDAP / Active Directory:** `source: LDAP_GROUP` matches the full DN of a `memberOf` group
(`cn=sales-team,ou=groups,dc=acme,dc=com`), not just the CN, to avoid collisions across OUs.

## 6. Annotating host code

> Deeper guidance — keeping all annotation text and limits in central constant classes, AI contract interfaces,
> complete scenarios for every annotation, a catalog contract test and a review checklist — is in
> [annotation-best-practices.md](annotation-best-practices.md).

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

Everything is on once the starter is on the classpath; switches exist only where a feature has a cost or a
privacy impact:

```yaml
dynamic:
  ai:
    agent:
      enabled: true              # master switch (default true); false makes the whole library inert
      mcp:
        enabled: true            # serves POST /dynamic-ai/mcp (default false) — §9
        workspace-id: 0193…      # workspace used when a request sends no X-DAI-Workspace header
      conversations:
        enabled: true            # record redacted transcripts (default false)
      write:
        enabled: true            # reviewed change proposals from tools (default false) — §6
```

The dynamic endpoints, agents and the admin API have no separate switch: they are inert until something is
published and granted. Which *capabilities* an environment allows (authoring, introspection, playground,
reviewed writes …) is decided by the environment tier (§8) and shown at `GET /dynamic-ai/admin/api/v1/me`.

## 8. Environment tiers and the PROD lock-down

`dynamic.ai.agent.environment.tier` is `DEV`, `TEST`, `STAGE`, or `PROD`; **leaving it unset resolves to
`UNKNOWN`, which is treated as `PROD`** (fail-closed, LLD-12 §2.1, OQ-19) — always set it explicitly, even in
DEV. Active Spring profiles matching `prod-profile-patterns` (default `prod,production,live,prd,*-prod`) can
only make the resolved tier *stricter*, never looser: an explicit `tier: dev` alongside an active `prod`
profile still resolves to PROD, logged as `CRITICAL` and audited as `ENVIRONMENT_CONFLICT`.

In PROD, **authoring, introspection, query preview/explain and the playground are disabled** (LLD-12 §2.2): the
endpoints answer `403 capability disabled` whatever the caller's roles; `GET /dynamic-ai/admin/api/v1/me` lists
the capabilities of the environment. Published
endpoints/agents/tools, reviewed writes, MCP, and ops views (usage, audit, kill switches) all continue to work
in PROD; only the "build/inspect the configuration live" surfaces are locked. Config changes in PROD go
through a signed bundle import promoted from STAGE, not the live UI (LLD-09 §5, LLD-12 §2.2).

A time-boxed break-glass override (named capabilities, mandatory expiry within 72 hours, reason, every action
audited) is modelled in the core (`ProductionOverride`, LLD-12 §2.3) but **not yet configurable** (OQ-53): today
the way to change a PROD configuration is a reviewed publish from an environment that allows authoring.

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

**Enabling it.** Off by default. Set `dynamic.ai.agent.mcp.enabled=true` and make the endpoint reachable:

```yaml
dynamic.ai.agent.mcp:
  enabled: true
  workspace-id: <uuid>            # or require every client to send the X-DAI-Workspace header
  resource-uri: https://host.example.com/dynamic-ai/mcp   # audience of the tokens; used in the metadata
  authorization-servers: [https://login.example.com/realms/acme]
  allowed-origins: []             # Origin values allowed for browser clients; requests without Origin are fine
  max-request-bytes: 1048576
  require-approved-client: true   # default; false only for trusted networks
```

The endpoint is **stateless** (`POST` only, one JSON-RPC message per request, JSON responses, no
`Mcp-Session-Id`; `GET`/`DELETE` answer 405), so any replica behind a plain round-robin balancer can serve it
(ADR-0021). `transport: STATEFUL` is not implemented and logs a warning. Authentication is **your** Spring
Security filter chain: configure it as an OAuth2 resource server (audience = `resource-uri`, see
`McpAudienceValidators`) or with API keys, and require authentication for `/dynamic-ai/mcp`; permit
`/.well-known/oauth-protected-resource/dynamic-ai/mcp` without authentication. Unauthenticated calls get `401`
with `WWW-Authenticate: Bearer resource_metadata="…"`.

**Tools.** A tool is offered only if a published `TOOL_BINDING` resource has `mcpExposed: true`, the caller's
token has the scope and the caller holds the grant (`tool:invoke`, `agent:invoke`, plus `data:write-propose` for
proposal tools). A tool the caller may not use looks exactly like an unknown tool. Every call runs as the caller,
in the read-only scope, with the binding's argument constraints applied, and is recorded in `dai_tool_invocation`
(hashes only) under a `dai_mcp_request` row. Writes never execute: a PROPOSE tool records a reviewable proposal
(owned by the caller, `dynamic.ai.agent.write.enabled=true` needed, otherwise it answers `writes_disabled`); the
caller confirms it through the review API, which then runs your operation **through your own Spring bean, as that
user** (your `@PreAuthorize`, transactions, `@Version`, auditing and Envers see the real user). A host `OptimisticLock`
failure is reported as a conflict. **Record versions:** name the id argument in the tool binding
(`entityIdArgument`) and the proposal remembers the record's version (JPA `@Version`, or a hash of the exposed,
non-sensitive attributes when the entity has none); if the record changed before the user confirmed, the change is
reported as a conflict and your operation is not called. Register a `VersioningAdapter` bean for Envers or a history
table to be consulted first. Applying is refused unless `write.enabled=true`, the capability `REVIEWED_WRITES`
is on for the environment, and the user still holds `data:write-confirm`. Optional: `write.capture-before-values`
(store the record's exposed, non-sensitive values for the review diff; row-level visibility is not applied) and
`write.require-base-version`. Not yet: Envers/history-table adapters, editing (OQ-36).

**Not yet:** MCP resources and prompts, server-initiated notifications (`tools/list_changed`), per-client rate
limits, `insufficient_scope` step-up challenges (a tool outside the token's scopes is simply not listed), and the
STDIO bridge (OQ-49).

### Walkthrough: expose an existing method as an MCP tool

> **Status.** This describes the implemented design end to end. The code has not been compiled or run yet (see the
> project status), so treat the exact responses as the intended shape; report any difference.

The path is: **annotate the method → find it in the catalog → publish a tool binding → grant it → call it over MCP.**
Nothing is reachable until every step is done (default deny), and the model can never call anything you did not publish.
An existing REST endpoint is not exposed as such: expose the **service method behind it** (the endpoint's own
`@PreAuthorize`, validation and transactions live there and are kept, because the method is called through its Spring
proxy, as the calling user).

**0. Prerequisites**

- `dynamic.ai.agent.environment.tier: dev` (or `test`). Authoring and catalog browsing are **off in PROD, and an unset tier
  counts as PROD** (§8); publishing needs the `CONFIG_CHANGES_UI` capability, browsing the catalog `INTROSPECTION`.
- A workspace id (`{ws}` below) and **two** admin users: one with `tool:author` and `tool:publish`, one with
  `review:approve`. The author can never approve their own revision (403); one approval is needed by default
  (`dynamic.ai.agent.review.required-approvals`).
- Your Spring Security chain authenticates the admin API (`/dynamic-ai/admin/**`) and the MCP endpoint (§5, §9).

**1. Annotate the method** (a Spring bean; the method must be `public`):

```java
@Service
public class OrderService {

    @AiExposedAction(intent = "Find a customer's orders, optionally by status", keywords = {"orders", "status"})
    @PreAuthorize("hasAuthority('orders:read')")                       // your own security still applies
    public List<OrderDto> findOrders(@AiParam(description = "Customer id") UUID customerId,
                                     @AiParam(description = "Order status", required = false) OrderStatus status) {
        ...
    }
}
```

`readOnly` defaults to `true`. Use `@AiParam(sensitive = true)` for arguments that must never be echoed, and `name` to
choose the tool name (`^[a-z][a-z0-9_]{2,63}$`; the default is derived from the method). Restart the host: the startup
scan builds the catalog.

**2. Find the operation reference**

```bash
curl -H "Authorization: Bearer $ADMIN" \
  "$BASE/dynamic-ai/admin/api/v1/catalog/operations?q=findOrders"
# -> ... "ref": "op:com.acme.orders.service.OrderService#findOrders(java.util.UUID,com.acme.orders.OrderStatus)",
#        "toolName": "find_orders", "readOnly": true, "enabled": true ...
```

**3. Create the tool binding** (a `TOOL_BINDING` resource; `specJson` is the spec **as a JSON string**):

```bash
curl -X POST -H "Authorization: Bearer $AUTHOR" -H "Content-Type: application/json" \
  "$BASE/dynamic-ai/admin/api/v1/workspaces/$WS/resources" -d '{
  "kind": "TOOL_BINDING",
  "slug": "find-orders",
  "changeSummary": "Expose findOrders over MCP",
  "specJson": "{\"toolName\":\"find_orders\",\"source\":{\"kind\":\"operation\",\"ref\":\"op:com.acme.orders.service.OrderService#findOrders(java.util.UUID,com.acme.orders.OrderStatus)\"},\"argConstraints\":{\"customerId\":{\"kind\":\"principalAttr\",\"attr\":\"customerId\"}},\"mcpExposed\":true}"
}'
# 201 with an ETag (the draft's row version), and a body holding the resource id (resourceId) and the draft revision id (id)
```

What the spec says (full format in LLD-07 §2):

| Key | Meaning |
|---|---|
| `toolName` | name the model and MCP clients see |
| `source` | `{"kind":"operation","ref":"op:…"}` (only operations can be PROPOSE tools; `query` and `agent` sources are read-only) |
| `argConstraints` | arguments **the server decides**: `principalAttr` (taken from the caller's identity attributes, which you map from token claims with `dynamic.ai.agent.security.attribute-claims`; a caller without it is refused), `literal`, or a numeric `range`. The model cannot override them, so use them for tenant or owner ids |
| `mcpExposed` | `true` = offered over MCP (default `false`: the tool is then only usable by agents) |
| `timeoutSeconds`, `maxCallsPerTurn`, `result.maxChars`, `writeMode`, `change`, `entityIdArgument` | limits and write behaviour |

**4. Submit, approve, publish** (`If-Match` is the `ETag` returned when the draft was created, e.g. `"0"`; 428 if missing, 412 if stale):

```bash
R=$BASE/dynamic-ai/admin/api/v1/workspaces/$WS/resources/$RESOURCE_ID/revisions/$REVISION_ID
curl -X POST -H "Authorization: Bearer $AUTHOR"   -H 'If-Match: "0"' -H "Content-Type: application/json" -d '{}' "$R:submit"
curl -X POST -H "Authorization: Bearer $APPROVER" -H "Content-Type: application/json" -d '{"comment":"ok"}'   "$R:approve"
curl -X POST -H "Authorization: Bearer $AUTHOR"   -H "Content-Type: application/json" -d '{"reason":"go live"}' "$R:publish"
```

Publishing creates a new generation of the live set; every node picks it up within
`dynamic.ai.agent.store.maintenance.snapshot-poll-interval` (default 5 s). Roll back with
`POST /dynamic-ai/admin/api/v1/cluster/generations/{n}:rollback`; take a tool away immediately with `:suspend` on the
resource (allowed in every environment).

**5. Grant the tool to callers.** A caller sees and can call the tool only if they hold `tool:invoke` (and the token has
the scope `dai.mcp.read`):

```bash
curl -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  "$BASE/dynamic-ai/admin/api/v1/workspaces/$WS/grants" \
  -d '{"principalId":"<user or group id>","permission":"tool:invoke","targetType":"WORKSPACE"}'
```

Use `targetType: RESOURCE` with the binding's `resourceId` to grant just this tool.

**6. Enable the endpoint and call it**

```yaml
dynamic.ai.agent.mcp:
  enabled: true
  workspace-id: <ws>                  # or send X-DAI-Workspace on every request
  require-approved-client: false      # see the note below
```

```bash
H=(-H "Authorization: Bearer $USER_TOKEN" -H "X-DAI-Workspace: $WS" -H "Content-Type: application/json")
curl "${H[@]}" $BASE/dynamic-ai/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}'
curl "${H[@]}" $BASE/dynamic-ai/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
curl "${H[@]}" $BASE/dynamic-ai/mcp -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_orders","arguments":{"status":"PAID"}}}'
```

`customerId` is not in the call: the binding fills it from the caller's identity. Any MCP client (Claude Desktop through
the stdio bridge is not available yet; MCP Inspector and SDK clients work over HTTP) points at `$BASE/dynamic-ai/mcp`
with the same headers. Every call is recorded (admin trace API: `…/workspaces/{ws}/traces/mcp-requests`).

> **Approved MCP clients.** With `require-approved-client: true` (the default) an OAuth token's `client_id` must be an
> approved client of the workspace, but there is no admin API or UI to approve one yet (OQ-49): insert the client into
> `dai_mcp_client` yourself, or set the flag to `false` on trusted networks. API-key service accounts of the workspace
> are always accepted, but API-key issuance is not wired yet either (OQ-37).

**7. A tool that changes data.** Annotate with `readOnly = false` and bind it as a proposing tool: it never writes by
itself; it records a proposal that the user confirms.

```json
{"toolName":"update_order_status",
 "source":{"kind":"operation","ref":"op:com.acme.orders.service.OrderService#updateStatus(java.util.UUID,com.acme.orders.OrderStatus)"},
 "writeMode":"PROPOSE", "change":"update", "entityIdArgument":"orderId", "mcpExposed":true}
```

Set `dynamic.ai.agent.write.enabled=true` (default off) and grant `data:write-propose` next to `tool:invoke`. The tool answers
`status: proposed` with a proposal id. The **owner** reviews it at `GET /dynamic-ai/api/proposals?scope=mine`, then
`POST …/proposals/{id}:confirm` with the `contentHash` they reviewed (needs `data:write-confirm`); this runs your method
as that user and returns `APPLIED`, `CONFLICT` (the record changed since, see `entityIdArgument`) or `FAILED`.

**When something is missing or wrong**

| Symptom | Likely cause |
|---|---|
| Operation not in the catalog list | method not `public` or `static`, the class is not a Spring bean, `@AiExposedAction` missing, an invalid or duplicate tool name (the scan reports it as an issue), or the host was not restarted |
| 403 `capability-disabled` on create/publish/browse | environment tier is PROD or unset (§8) |
| 403 on `:approve` | you approved your own revision; use a second user |
| 428 / 412 on submit | missing or stale `If-Match`; re-read the revision and use its current `ETag` |
| Tool not in `tools/list` | not published yet (wait one poll interval), `mcpExposed` is `false`, the caller has no `tool:invoke` grant or lacks the `dai.mcp.read` scope, or the binding was skipped: **a wrong `ref` is not rejected at publish time** (spec validation per kind is open, OQ-41), the tool is just skipped and a warning is logged (`Tool binding … skipped`, `cannot resolve delegate`) |
| 401 with `WWW-Authenticate` | no valid token; check the resource server and the audience (§5) |
| 403 "MCP client not approved" | see the note in step 6 |
| `writes_disabled` / `proposal_unavailable` / `operation_without_entity` | `write.enabled` is off, or the operation is not linked to an entity (annotate the entity with `@AiContext`) |
| Tool answers `constraint_unsatisfied` | the caller's identity lacks the attribute named in `principalAttr`: map it from a token claim with `dynamic.ai.agent.security.attribute-claims.<attribute>=<claim>` |

### Conversation history (opt-in)

Users can list, read, close and erase their own conversations through `/dynamic-ai/api/conversations`, but
transcripts contain what users typed, so nothing is stored until you enable it:

```yaml
dynamic.ai.agent.conversations:
  enabled: true          # default false
  retention: 30d         # kept this long after the last activity; 1ms..3660d
  max-stored-chars: 100000
  purge-interval: 15m    # expired conversations are deleted (runs even while enabled=false)
  erase-mode: RETAIN_FOR_AUDIT   # default; HARD deletes the transcript when the user erases
  audit-retention: 90d   # how long an erased conversation is kept for audit (1ms..3660d)
```

**Erase keeps the transcript for audit by default.** When a user erases a conversation it disappears from their
history (the title is hidden too), the model's memory of it is deleted, and nothing is written to it again. With
`RETAIN_FOR_AUDIT` the redacted transcript is kept for `audit-retention` and then purged by the same job as expired
conversations; with `HARD` it is deleted at once. The audit trail records the erase and its mode either way.

Auditors see the workspace's conversations, including erased ones still on hold, at
`/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/conversations`:

| Call | Permission | What it does |
|---|---|---|
| `GET …/conversations?status=ERASED&principalId=…` | `AUDIT_READ` | lists conversations with `erasedAt`, `auditHoldUntil`, `retentionUntil` |
| `GET …/conversations/{id}` | `AUDIT_READ` | the stored (redacted) transcript; every read is itself audited (`CONVERSATION_READ`) |
| `DELETE …/conversations/{id}?reason=…` | `WORKSPACE_ADMIN` | deletes the conversation, its messages and its model memory now, hold or not (`CONVERSATION_PURGED`) |

> **Privacy.** Under `RETAIN_FOR_AUDIT` a user's erase is not a deletion: the content stays for `audit-retention`.
> Tell users so in your privacy notice, make sure you have a legal basis for the hold, and handle a data-subject
> erasure request with the admin `DELETE` above (or run with `erase-mode: HARD`).

Each successful turn stores the user message and the answer, after redaction: a message that contains a
credential (private key, API or cloud token, JWT, URL with credentials, password assignment) is replaced by a
placeholder, never stored in part. Refused, failed and cancelled turns store nothing. With output PII redaction on
(the default, see "Guardrails" below) the stored answer is the redacted one users saw; the user message is
PII-redacted only with `redact-input-pii` (or the agent's `piiRedactionInput`). The stored conversation uses the conversation id the client sends and is found again through a
hash of (principal, agent, conversation id), so another user cannot read or append to it. A conversation the user
erased or closed is not written to again. This is the history shown to users, not the memory the model reads.

### Chat memory (what the model remembers)

Follow-up questions work on any replica and after a restart: the window the model reads is kept in PostgreSQL
(`dai_chat_memory_message`) as soon as the `dynamic_ai` store exists. It is redacted like transcripts, keyed by a
hash of (workspace, agent, principal, conversation), and expires on its own clock:

```yaml
dynamic.ai.agent.memory:
  persistent: true       # default; false keeps the window in this node's heap (lost on restart, per node)
  retention: 24h         # kept this long after the last message; 1ms..3660d
  max-stored-chars: 100000
```

Expired memories are deleted by the same background job as expired conversations.

### Guardrails: prompt validation, PII redaction and structured answers

Every agent turn passes the guardrails of LLD-06 §8. The host sets a floor; an agent's spec can switch more on,
never off.

```yaml
dynamic.ai.agent.guardrails:
  threat-detection: true     # default; reject prompt injection, jailbreaks, SQL/script/command injection,
                             # exfiltration requests and hidden/encoded payloads (code input_malicious)
  business-scope: false      # reject prompts unrelated to your domain (code off_topic); see below
  min-relevance: 0.25        # share of a prompt's content words that must relate to the domain
  min-terms-to-judge: 2      # shorter prompts (greetings, follow-ups) are not judged for scope
  scope-keywords: []         # extra in-scope terms for every agent, e.g. [returns, warranty]
  redact-input-pii: false    # true: the model provider never sees personal data typed by users
  redact-output-pii: true    # default; answers never show e-mails, phones, cards, IBANs, SSNs, IPs, credentials
  structured-display: true   # default; every answer also carries a structured display tree
```

**Business scope is judged against what you told the library about your code**: the `@AiContext` names,
descriptions and keywords of your entities, the `@AiEntityProperty` meanings, and the intents and keywords of your
exposed operations (after policy layers), plus the agent's `topicAllowList` and `scopeKeywords`. Good descriptions
and keywords make the check accurate; enable it per agent first, watch the `off_topic` rejections in the logs, then
consider the host-wide switch. A rejected prompt never reaches the model: a streamed turn ends with an error event
(`off-topic`, `input-malicious`), a synchronous turn answers `[off_topic] …` like the other input guardrails.

Per agent (in the agent spec):

```json
{"guardrails": {
   "piiRedactionInput": true,
   "inputValidation": {"threatDetection": true, "businessScope": true, "minRelevance": 0.3,
                       "scopeKeywords": ["returns", "refund"]}},
 "output": {"mode": "text", "display": {"version": 1, "blocks": [
   {"type": "text", "title": "Answer"},
   {"type": "fields", "title": "Customer", "source": "customer", "fields": [
     {"path": "name", "label": "Name"},
     {"path": "cardNumber", "label": "Card", "mask": "partial"}]},
   {"type": "table", "title": "Orders", "source": "orders", "maxRows": 20, "columns": [
     {"path": "id", "label": "Order #"},
     {"path": "total", "label": "Total", "format": "number"},
     {"path": "shippedOn", "label": "Shipped", "format": "date"}]}]}}}
```

**The display template is decided by the backend, not the model.** It names the values to show (dot paths into the
answer's JSON: the whole answer for `JSON_SCHEMA` agents, or a fenced `json` block in a text answer — say so in the
system prompt) with labels, formats and masks. Anything the template does not name is not displayed. Without a
template the answer is laid out automatically (prose, object → fields, array of objects → table). In both cases
values under sensitive keys (`sensitive = true` attributes, disabled attributes, credential-like names) are always
masked and every value is PII-redacted. Node types: `text` (`title`), `fields` (`title`, `source`, `fields`),
`table` (`title`, `source`, `columns`, `maxRows` 1–1000, default 100), `section` (`title`, `blocks`); field keys
`path`, `label`, `format` (`text|number|boolean|date|datetime`), `mask` (`none|partial|full`). An invalid template
makes that agent fail to load, with every error and its JSON path in the log.

Where clients find it: the sync response has a `display` object next to `message`; the stream sends a
`ui.component` event with `componentType: "structured-response"` (its `payload` is the same tree as a JSON string)
before `usage`/`turn.end`. Shape: `{"version":1,"blocks":[{"type":"text","text":…},{"type":"fields","title":…,
"items":[{"key","label","format","value"}]},{"type":"table","columns":[{"key","label","format"}],"rows":[[…]],
"totalRows":n,"truncated":bool},{"type":"section","title":…,"blocks":[…]}],"redactions":{"EMAIL":1},"masked":0}`.

**Extending it** — declare beans; they are added to the built-in ones:

```java
@Bean
PromptValidator noInvestmentAdvice() {          // runs after the built-in validators; first rejection wins
    return request -> request.prompt().toLowerCase(Locale.ROOT).contains("stock tip")
            ? PromptVerdict.reject("regulated_advice", "I can't give investment advice.", List.of("advice"))
            : PromptVerdict.allow();
}

@Bean
PiiDetector employeeNumbers() {                 // your own identifier formats
    Pattern p = Pattern.compile("\\bEMP-\\d{6}\\b");
    return text -> p.matcher(text).results()
            .map(m -> new PiiMatch(PiiType.OTHER, m.start(), m.end())).toList();
}
```

Validators and detectors must be thread-safe, fast and must not log the text they see; one that throws rejects the
prompt / withholds the answer (fail closed). Replace the whole pipeline with your own `TurnSafety` or `PiiRedactor`
bean if you need to.

## 10. Operations

- **Health**: a `HealthContributor` named `dynamicAi` reports catalog state, snapshot generation/lag, config
  store reachability, and model-provider breaker states. It does **not** join the host's readiness group by
  default (a degraded AI feature must not take the host's `/actuator/health/readiness` down) — opt in only if
  your deployment explicitly wants that coupling.
- **Metrics**: Micrometer, prefix `dynamic.ai.agent.*`, registered into the host's own `MeterRegistry` (the
  library never starts its own metrics server). Key meters: `.endpoint.requests`, `.query.executions`,
  `.agent.turns`, `.agent.tool.calls`, `.llm.tokens`, `.llm.cost`, `.budget.utilization`,
  `.ratelimit.rejections`, `.snapshot.generation`/`.snapshot.lag`, `.authz.decisions` (LLD-10 §2). Spans:
  `dai.endpoint`, `dai.query`, `dai.agent.turn`, `dai.tool`, `dai.snapshot.apply` (LLD-10 §3). Implemented so far:
  `dai.agent.turn`, `dai.tool`, `dai.mcp` (meters `dynamic.ai.agent.turn|tool|mcp`), carrying the turn, model-call and
  MCP-request ids so a trace in your tracing backend (Tempo, Jaeger, Langfuse via OTLP, …) can be matched to the rows
  in the admin trace API; add `micrometer-tracing` with your OpenTelemetry or Brave bridge to get them as spans.
- **Partition maintenance**: runs on `dynamic.ai.agent.store.maintenance.cron` (default `0 17 3 * * *`, UTC; disable
  with `store.maintenance.enabled=false`),
  advisory-locked so exactly one cluster node does the work per run (`dai_job_run` records the outcome). Verify
  it is actually running with `scripts/db/postgresql/04_verify.sql` §3–4 (empty `DEFAULT` partitions, expected
  partitions present).
- **Retention**: telemetry and audit are kept by monthly partition and dropped by the partition maintenance with
  the defaults of `docs/lld/15-database-schema.md` §10 (not yet configurable per host, OQ-31); conversations and
  chat memory follow `conversations.retention` / `audit-retention` and `memory.retention`.
- **Backups**: continuous archiving (WAL + base backups) is recommended over `pg_dump` alone for anything
  beyond a dev snapshot; see `docs/lld/15-database-schema.md` §14 for the full restore-drill checklist,
  including why roles/grants must be recreated (`scripts/db/postgresql/02_`/`03_`) before an application can
  reconnect to a restored store.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App fails to start with a Flyway `permission denied for schema dynamic_ai` | Migration credentials point at `dai_app` (DML-only) instead of `dai_migrator` | Point the migrating `DataSource` at `dai_migrator` (or let a DBA run Flyway and set `dynamic.ai.agent.store.migrate=false`), or switch to DBA-run migrations (`store.migrate=false`) |
| Runtime queries fail with `permission denied for table dai_...` | `02_create_schema_and_privileges.sql` never ran, or ran *after* Flyway already created the tables | Run `scripts/db/postgresql/03_post_migration_grants.sql` as a catch-up |
| Authoring/introspection/playground unexpectedly disabled in what you believe is DEV | `dynamic.ai.agent.environment.tier` unset ⇒ resolves to `UNKNOWN` ⇒ treated as `PROD` (§8) | Set `environment.tier: dev` explicitly |
| Startup logs `ENVIRONMENT_CONFLICT` / `CONFIG_STORE_IDENTITY_MISMATCH` | An active Spring profile matches a prod pattern despite an explicit non-prod tier, or this app is pointed at a store whose `dai_environment` row belongs to a different environment (e.g. a prod backup restored into stage) | Fix the profile/tier mismatch, or confirm you are connecting to the intended store (`scripts/db/postgresql/04_verify.sql` §2) |
| A tool call always returns `status: not_permitted` even though the method is annotated | Default-deny (SEC-01 §1): annotation alone only makes something *eligible*; the caller also needs a `dai_grant` | Grant the permission via the admin API/UI, or a bootstrap `role-mappings` entry that reaches a role with that permission (SEC-01 §5) |
| An `@AiExposedAction` method never appears as a tool, with a `ScanIssue` about parameter names | Host compiled without `-parameters` and no `@AiParam(name=...)` given | Enable `-parameters` (default for Spring Boot's Maven/Gradle plugins) or add explicit `name` to every `@AiParam` |
| A list-returning action is excluded with `UNBOUNDED_LIST_ACTION` | No `Pageable`/`Limit` parameter, no paged return type, and no `@AiParam` acting as a limit | Add a limit parameter or paginated return type (LLD-02 §4) |
| MCP client gets `401` with `WWW-Authenticate: Bearer resource_metadata="..."` | No token, wrong audience, or the client isn't in `dai_mcp_client` as `APPROVED` yet | Check the token's `aud` claim against the MCP resource URI; have an admin approve the client (§9) |
| Rows keep landing in `dai_agent_turn_pdefault` (or the other `_pdefault` partitions) | The partition-maintenance job is disabled, failing, or badly behind schedule | `scripts/db/postgresql/04_verify.sql` §3–4; check `dai_job_run` for recent failures; confirm `maintenance.cron` is actually configured and the job isn't losing the advisory lock race indefinitely |
| A confirmed write proposal never applies, stuck in `APPLYING` | The node that confirmed it crashed before recording `APPLIED`/`FAILED` | This is exactly what `ix_change_proposal_applying` (LLD-15 §6.4) exists to find — the crash-reconciliation job should pick it up on its next run (LLD-11 §10); if it doesn't, that job itself needs investigating |
