# LLD-01: Module Structure & Auto-Configuration

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | lld-chief-architect |
| Module(s) | all; esp. `-autoconfigure`, `-spring-boot-starter`, `-bom` |
| Related features | F-01, F-04 |
| Related ADRs | ADR-0001, ADR-0002, ADR-0007 |

## 1. Purpose & responsibilities
Define module boundaries, dependency direction, how the library boots inside a host,
and how the host overrides or disables every part. Does **not** define component internals.

## 2. Module responsibilities & allowed dependencies

| Module | Contains | May depend on | Scope in host |
|--------|----------|---------------|---------------|
| `annotations` | `@AiContext`, `@AiEntityProperty`, `@AiExposedAction`, `@AiParam`, `@AiQueryConstraints` (RUNTIME retention) | nothing | compile |
| `catalog-model` | Catalog records, JSON schema, reader/validator | Jackson 3 (optional), JSON-schema lib | runtime |
| `javadoc-enricher` (optional, v1.x) | JSR-269 processor adding Javadoc param text where annotations lack it (ADR-0013) | `annotations`, `catalog-model` | annotationProcessor only |
| `core` | Domain model, use cases, ports (SPI) | `catalog-model`, JSpecify | runtime |
| `security` | AuthZ engine, permission evaluator, `AuthorityMapper`, audit writer port | `core`, spring-security-core | runtime |
| `jpa` | Query engine (Criteria), config store repositories, Flyway migrations | `core`, jakarta.persistence, spring-jdbc | runtime |
| `webmvc` | Dynamic route registrar, generic handler, admin REST controllers, problem mapping | `core`, `security`, spring-webmvc | runtime |
| `ai` | Agent runtime, tool bridge, advisors | `core`, `security`, spring-ai-client-chat | runtime |
| `mcp` | MCP server exposure & client consumption | `ai`, spring-ai-mcp | runtime (optional) |
| `admin-ui` | Static SPA under `META-INF/resources/dynamic-ai/admin/` | none | runtime (optional) |
| `review-ui` | Web Components `<saimcp-*>` + JS client under `META-INF/resources/dynamic-ai/components/` (LLD-11 §7) | none | runtime (optional) |
| `autoconfigure` | `@AutoConfiguration` classes, `@ConfigurationProperties` | all adapters as **optional** deps | runtime |
| `spring-boot-starter` | Aggregates autoconfigure + default adapters | — | runtime |
| `bom` | Version alignment | — | import |

Enforcement: ArchUnit rules in each module's test suite (core must not import
`org.springframework.web..`, `jakarta.persistence..`, `org.springframework.ai..`).

## 3. Auto-configuration classes

Registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

| Class | Conditions | Ordering | Provides |
|-------|-----------|----------|----------|
| `DynamicAiCoreAutoConfiguration` | `@ConditionalOnBooleanProperty("dynamic.ai.agent.enabled", matchIfMissing=true)` | first | `CatalogRegistry`, `SnapshotHolder`, `Clock`, invocation-context infra |
| `DynamicAiSecurityAutoConfiguration` | `@ConditionalOnClass(SecurityFilterChain)` | after Boot `SecurityAutoConfiguration` | `SecurityFilterChain` for `/dynamic-ai/**` (ordered), `AuthorityMapper`, `PermissionEvaluator`, `AuditSink` |
| `DynamicAiJpaAutoConfiguration` | `@ConditionalOnClass(EntityManager)`, `@ConditionalOnBean(EntityManagerFactory)` | after `HibernateJpaAutoConfiguration` | `QueryEngine`, `ConfigStore`, Flyway for `dai_*` |
| `DynamicAiWebMvcAutoConfiguration` | `@ConditionalOnWebApplication(type=SERVLET)` + `dynamic.ai.agent.endpoints.enabled` | after WebMvc | `DynamicRouteRegistrar`, `GenericDynamicHandler`, admin API |
| `DynamicAiAgentAutoConfiguration` | `@ConditionalOnClass(ChatClient)` + `dynamic.ai.agent.agents.enabled` | after Spring AI model auto-configs | `AgentRuntime`, `ToolBridge`, `ModelRouter` |
| `DynamicAiMcpAutoConfiguration` | `@ConditionalOnClass(McpSyncServer)` + `dynamic.ai.agent.mcp.server.enabled=false` default | after agent | MCP tool provider |
| `DynamicAiAdminUiAutoConfiguration` | UI jar present + `dynamic.ai.agent.admin.ui.enabled` | after webmvc | Resource handler, SPA fallback |
| `DynamicAiObservabilityAutoConfiguration` | `@ConditionalOnClass(MeterRegistry)` | last | meters, observation conventions |

Rules:
- Every bean `@ConditionalOnMissingBean` (by type, and by name where host may already
  have one of the same type, e.g. our `ObjectMapper` is **never** a bean — we use a
  private, qualified `dynamicAiJsonMapper`).
- No `@ComponentScan` in the library. No `@EnableJpaRepositories` over host packages —
  our JPA entities (config store) are registered via a dedicated
  `PersistenceManagedTypes` contribution **or** we use JDBC for the config store (ADR-0006
  leans JDBC to avoid touching the host's persistence unit — OQ-03).
- Never modify host beans (no `BeanPostProcessor` on host objects) except read-only
  inspection for the catalog resolver.
- Failure analyzers (`FailureAnalyzer`) for misconfigurations with actionable messages.

## 4. Configuration properties (root)

| Property | Default | Description |
|----------|---------|-------------|
| `dynamic.ai.agent.enabled` | `true` | Master switch; `false` ⇒ no beans at all |
| `dynamic.ai.agent.base-path` | `/dynamic-ai` | Root for all our HTTP paths |
| `dynamic.ai.agent.scan.base-packages` | `@SpringBootApplication` package | Where annotations are scanned (LLD-02 §3.2) |
| `dynamic.ai.agent.policy.locations` | `classpath:META-INF/dynamic-ai/ai-agent-policy.json` | Policy JSON layers (LLD-03 §4) |
| `dynamic.ai.agent.catalog.fail-on-invalid` | `false` | Fail startup vs. degrade |
| `dynamic.ai.agent.endpoints.enabled` | `false` | Dynamic data plane |
| `dynamic.ai.agent.agents.enabled` | `false` | Agent runtime |
| `dynamic.ai.agent.admin.enabled` | `false` | Control plane API |
| `dynamic.ai.agent.admin.ui.enabled` | `false` | Dashboard |
| `dynamic.ai.agent.mcp.server.enabled` | `false` | MCP server |
| `dynamic.ai.agent.store.schema` | `dynamic_ai` | DB schema for `dai_*` tables |
| `dynamic.ai.agent.store.table-prefix` | `dai_` | Table prefix |
| `dynamic.ai.agent.store.datasource` | (host primary) | Bean name of an alternate `DataSource` |

Everything off by default except core → adding the JAR is inert (F-01).
`spring-boot-configuration-processor` generates `META-INF/spring-configuration-metadata.json` for IDE completion;
`additional-spring-configuration-metadata.json` adds value hints (enums, tier names, provider IDs) and `deprecation`
entries with `replacement` for any renamed property (important if OQ-17 renames the namespace).

### 4.1 Initialisation strategy (idle = invisible, active = predictable)
| Situation | Strategy | Why |
|-----------|----------|-----|
| Feature disabled (default for everything but core) | **No beans at all** (conditions) | Stronger than `@Lazy`: zero memory, zero startup cost, zero attack surface |
| Feature enabled | **Eager** initialisation + warm-up (LLD-14 §6) | Misconfiguration fails at deploy time, not on the first user request; first request isn't slow |
| Heavy optional resources inside an enabled feature (local embedding model, large tokenizer files) | Loaded on first use via `ObjectProvider`/holder, or eagerly when `warmup.<resource>=eager` | Pay only if the resource is actually used |
We never set `spring.main.lazy-initialization` (host-global) and avoid `@Lazy` on injection points, which only swaps the cost for a proxy and moves failures to runtime.

## 5. Startup sequence
```
ApplicationContext refresh
 ├─ Core: load catalog resources → validate schema → build CatalogRegistry (unresolved)
 ├─ JPA: run Flyway (dai_ history table `dai_schema_history`)
 ├─ SmartInitializingSingleton: resolve catalog refs vs Metamodel + bean definitions
 ├─ ApplicationReadyEvent (ordered):
 │    1. load latest PublishedSnapshot  2. drift validation  3. register routes
 │    4. warm agent definitions  5. start SnapshotWatcher
 └─ Readiness: `dynamicAi` HealthIndicator + AvailabilityChangeEvent → ACCEPTING_TRAFFIC
```
If step 1 fails (DB down): start with **empty snapshot**, health = `OUT_OF_SERVICE` for our
contributor only (configurable whether it affects host readiness group).

## 6. Concurrency model (ADR-0007)
- Servlet host decides request threads; we recommend `spring.threads.virtual.enabled=true`.
- LLM calls and tool fan-out use a library-owned virtual-thread executor wrapped in a
  **semaphore bulkhead** per resource class (LLM, DB, MCP) — virtual threads remove
  thread starvation but not downstream overload.
- Invocation context (principal, workspace, trace, budget) carried in a `ScopedValue`;
  Spring Security context propagated with `DelegatingSecurityContextExecutor`.
- No `synchronized` around blocking I/O (pinning risk reduced in 24+ but keep rule).

## 7. Failure modes
| Failure | Behavior |
|---------|----------|
| No catalog on classpath | Warn once; catalog-dependent features show empty state |
| Spring AI absent | Agent features' auto-config silently skipped; admin UI hides agents |
| Host has its own `SecurityFilterChain` matching `/**` | Ours has higher precedence & `securityMatcher("/dynamic-ai/**")`; documented |
| Two catalog versions (multi-module) | Merge by element ID; duplicate ID with different hash ⇒ error |

## 8. Test strategy
- `ApplicationContextRunner` tests per auto-config (conditions on/off, host overrides).
- Sample host apps: (a) minimal JPA+OIDC, (b) LDAP session auth, (c) no-security (must refuse to enable admin plane).
- ArchUnit dependency rules.

## 9. Open questions
OQ-01 (WebFlux), OQ-02 (group/artifact id, base package), OQ-03 (JDBC vs JPA config store).
