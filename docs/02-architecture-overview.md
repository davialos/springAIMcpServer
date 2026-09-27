# 02 — Architecture Overview (HLD)

Owner: lld-chief-architect · Status: Draft v1

## 1. Shape of the system

The framework is **a library, not a service**. It runs inside the host JVM, shares the
host's `DataSource`, `EntityManager`, `SecurityContext`, and observability stack, and is
bootstrapped by Spring Boot auto-configuration.

```
                     ┌──────────────────────────── HOST SPRING BOOT APP (JVM) ───────────────────────────┐
  Browser (admin) ──►│ /dynamic-ai/admin/**  ┌──────────────────┐                                        │
                     │  (UI + Admin API) ───►│  CONTROL PLANE   │── drafts/publish ──► Config Store (dai_*)│
                     │                       └────────┬─────────┘                          ▲              │
                     │                                │ PublishedSnapshot events            │              │
                     │                                ▼                                     │              │
  Consumers ────────►│ /dynamic-ai/api/**    ┌──────────────────┐   ┌──────────────────┐   │              │
  (users, services)  │  (dynamic routes) ───►│ DATA PLANE       │──►│ Query Engine     │──►│ Host DB (JPA)│
                     │                       │ generic handler  │   └──────────────────┘                  │
                     │ /dynamic-ai/api/      │ + pipeline       │   ┌──────────────────┐   ┌───────────┐  │
                     │   agents/{slug}/chat ►│                  │──►│ AGENT RUNTIME    │──►│ LLM (port)│──► provider
                     │                       └──────────────────┘   │ ChatClient+tools │   └───────────┘  │
  MCP clients ──────►│ /dynamic-ai/mcp  ─────────────────────────────►│ Tool Bridge      │──► host beans    │
                     │                                              └──────────────────┘  (via proxies)   │
                     │  ┌────────────────────┐  ┌────────────────────┐  ┌─────────────────────────────┐    │
                     │  │ Metadata Registry  │◄─│ @Ai* scan + policy │  │ Security (AuthZ, audit,     │    │
                     │  │ (immutable graph)  │  │ (built by APT)     │  │ AuthorityMapper → host IAM) │    │
                     │  └────────────────────┘  └────────────────────┘  └─────────────────────────────┘    │
                     └────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Planes

| Plane | Paths | Who | What |
|-------|-------|-----|------|
| Code | — (annotations) | Host developer | `@AiContext`, `@AiExposedAction`, … scanned at startup (LLD-02) |
| Control | `/dynamic-ai/admin/**` | Admins, authors, approvers | CRUD drafts, review, publish, grants, audit |
| Data | `/dynamic-ai/api/**` | Consumers | Dynamic endpoints & agent chat |
| MCP | `/dynamic-ai/mcp` | MCP clients | Tools/agents via MCP |

Each plane has its own authorization policy (see security/01).

## 3. Module map (Maven multi-module; detail in lld/01)

```
spring-ai-mcp-server-common-bom
spring-ai-mcp-server-common-annotations        ← @AiContext, @AiEntityProperty, @AiExposedAction, @AiParam, @AiQueryConstraints (no deps)
spring-ai-mcp-server-common-catalog-model      ← catalog records + JSON schema (no Spring)
spring-ai-mcp-server-common-javadoc-enricher   ← optional (v1.x) Javadoc enrichment processor
spring-ai-mcp-server-common-core               ← domain + use cases + ports (no Spring Web/JPA)
spring-ai-mcp-server-common-security           ← AuthZ engine, AuthorityMapper SPI, audit
spring-ai-mcp-server-common-jpa                ← query engine adapter, config store adapter (JPA/JDBC)
spring-ai-mcp-server-common-webmvc             ← dynamic endpoint adapter, admin API controllers
spring-ai-mcp-server-common-ai                 ← Spring AI adapter: agent runtime, tool bridge
spring-ai-mcp-server-common-mcp                ← MCP server/client adapter (optional)
spring-ai-mcp-server-common-admin-ui           ← prebuilt SPA static assets
spring-ai-mcp-server-common-review-ui          ← embeddable Web Components (display + write-review) + JS client
spring-ai-mcp-server-common-autoconfigure      ← @AutoConfiguration classes only
spring-ai-mcp-server-common-spring-boot-starter← dependency aggregator
spring-ai-mcp-server-common-maven-plugin / gradle-plugin (v1.x)
spring-ai-mcp-server-common-test               ← test kit (v1.x)
```

Dependency rule: adapters → core ← (nothing). `autoconfigure` → adapters. Core never
imports `org.springframework.web`, `jakarta.persistence`, or `org.springframework.ai`.

## 4. Core domain concepts (ubiquitous language)

| Term | Meaning |
|------|---------|
| **Catalog** | Build-time snapshot of exposed host elements (entities, attributes, relations, operations) with docs |
| **CatalogElementRef** | Stable ID of a catalog element, e.g. `entity:com.acme.Order`, `op:com.acme.OrderService#findByCustomer(java.lang.Long)` |
| **Workspace** | A team boundary that owns resources and has members |
| **Resource** | Versioned config object: `EndpointDefinition`, `QueryDefinition`, `AgentDefinition`, `ToolBinding`, `RowPolicy`, `Grant` |
| **Revision** | Immutable version of a resource; lifecycle DRAFT→IN_REVIEW→APPROVED→PUBLISHED |
| **PublishedSnapshot** | The complete, consistent set of published revisions the data plane serves (one generation number) |
| **Principal** | Caller identity resolved from the host `SecurityContext` + mapped roles/attributes |
| **Invocation** | One data-plane call (endpoint hit, chat turn, tool call) — the unit of audit & metering |

## 5. Key flows

### 5.1 Code annotation
1. Host developers annotate entities, fields, services and actions with `@Ai*` annotations (no build plugin).
2. CI golden-file test exports the catalog and diffs it (LLD-02 §5).

### 5.2 Startup
1. Auto-config checks `dynamic.ai.agent.enabled`.
2. Registry scans `@Ai*` annotations (bean factory + JPA metamodel), merges policy layers
   (policy JSON, published overlays, kill switches) into the effective catalog (LLD-02/03).
3. Config store migrates `dai_*` schema (Flyway, own history table).
4. Loads latest `PublishedSnapshot`; validates against catalog (drift report).
5. Registers dynamic routes; builds agent/tool caches. Readiness goes UP only after this.

### 5.3 Publish (control plane)
1. Author edits draft → submits → approver approves (policy decides if required).
2. Publish creates new snapshot generation `g+1` in one DB transaction.
3. Change notification (DB poll or pluggable bus) → every node loads `g+1`, swaps
   atomically (routes, agents, policies), reports applied generation.

### 5.4 Endpoint call (data plane)
`HTTP → SecurityFilterChain(/dynamic-ai/**) → GenericDynamicHandler → resolve route in
snapshot → AuthZ (perm:endpoint:invoke) → validate → rate-limit/budget → execute
(QueryExecutor | AgentInvoker | OperationInvoker) → mask/shape → audit → response`

### 5.5 Agent turn
`chat request → AuthZ(agent:invoke) → load AgentRevision → build ChatClient (model port,
advisors: guardrails, memory, RAG, ToolCallingAdvisor) → tools filtered by principal
grants → LLM ↔ tool loop (each tool call re-authorized, runs as caller, bounded) →
output guardrails/redaction → stream response → meter tokens/cost → audit`

### 5.6 Reviewed write (ADR-0009, LLD-11)
`mutating tool call / write endpoint → ChangeProposal (before-snapshot + host version token,
after-values, diff, validation; nothing written) → review UI component → user edits/confirms via
authenticated HTTP request (content hash) → [approver if policy] → re-authz + version check →
apply via host service method / EntityManager as the user → host @Version, AuditorAware,
Envers/history tables, triggers record the change → proposal APPLIED ↔ host revision → agent continues`

## 6. Cross-cutting decisions (summary; see ADRs)

| Topic | Decision | ADR |
|-------|----------|-----|
| Packaging | Starter + autoconfigure split, `AutoConfiguration.imports` | ADR-0001 |
| Architecture style | Hexagonal core, adapters per tech | ADR-0002 |
| Code knowledge | Runtime `@Ai*` annotations + JSON policy chain (supersedes Javadoc processor) | ADR-0013 |
| Streaming | Typed SSE events via `Flux` on WebMVC, POST + fetch client | ADR-0015 |
| MCP | Streamable HTTP in-host, OAuth 2.1 protected resource, no embedded STDIO | ADR-0016 |
| Parallel tools | Independent read calls of one model response run concurrently (bounded) | ADR-0017 |
| Audit | Standard (metadata + hashes) always; encrypted full-content evidence mode opt-in | ADR-0018 |
| AI read-only enforcement | Scoped read-only tx + Hibernate write veto on the AI path (no host-wide AOP) | ADR-0014 |
| Dynamic queries | Structured AST → Criteria API; no raw JPQL | ADR-0004 |
| Identity | Delegate to host Spring Security; map, never own | ADR-0005 |
| Config propagation | DB-backed snapshot generations + polling, pluggable bus | ADR-0006 |
| Concurrency | Virtual threads for I/O paths; ScopedValue for invocation context | ADR-0007 |
| Tool invocation | Through Spring proxies, runs-as-caller, ToolContext for identity | ADR-0008 |
| Writes | Only via user-reviewed change proposals, applied through host write paths reusing host versioning/audit | ADR-0009 |
| Naming | springAIMcpServerCommon, `spring-ai-mcp-server-common-*` | ADR-0010 |
| Environment containment | Composite tier detection, per-capability matrix, prod lock-down of authoring | ADR-0011 |
| Dependencies | Inherit Spring-managed libs; shade only internal-only ones | ADR-0012 |

## 7. Quality attribute targets (initial)

| Attribute | Target |
|-----------|--------|
| Data-plane overhead (excl. DB/LLM) | p99 < 5 ms |
| Publish propagation | ≤ 30 s all nodes (≤ 10 s for kill switch) |
| Startup overhead | < 1.5 s for catalog of 2 000 elements |
| Availability | Data plane serves last-good snapshot if config DB unavailable |
| LLM outage | Agents fail fast with 503 + problem type; endpoints not using LLM unaffected |
