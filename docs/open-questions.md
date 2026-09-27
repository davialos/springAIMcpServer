# Open Questions

Status legend: **OPEN** · **DEFERRED** (parked by product owner, discuss later) · **RESOLVED** (link to decision)

| ID | Question | Options / leaning | Owner | Blocks | Status |
|----|----------|-------------------|-------|--------|--------|
| OQ-01 | Support WebFlux hosts? | v1 servlet-only; WebFlux adapter later via `RouterFunction` registry | lld-chief-architect | LLD-04 | OPEN |
| OQ-02 | Product name, artifactIds, base package | Name **springAIMcpServerCommon**, artifacts `spring-ai-mcp-server-common-*` | user | ADR-0001 | RESOLVED → ADR-0010 |
| OQ-02b | Maven groupId / org reverse-domain for base package | `<org-domain>.springaimcpservercommon` | user | release | OPEN |
| OQ-03 | Config store: JDBC (own schema) vs JPA entities in host persistence unit | leaning JDBC (`JdbcClient`) | control-plane-designer | LLD-09 | OPEN |
| OQ-04 | Kotlin hosts (KSP processor)? | Runtime annotations work for Kotlin unchanged; no KSP needed | metadata-extraction-designer | LLD-02 | RESOLVED → ADR-0013 |
| OQ-05 | Use spring-ai community `mcp-security` or own OAuth2 resource-server config for MCP? | own Spring Security resource-server config (LLD-07 §5.3); evaluate mcp-security for PRM/CIMD helpers | agent-runtime-designer | LLD-07 | OPEN |
| OQ-06 | Expose Spring Data repository query methods directly as operations? | allow `@AiExposedAction` on repository interface methods; read-only only | metadata-extraction-designer | LLD-02 | OPEN |
| OQ-07 | Shared rate-limit/budget backend: Bucket4j (JCache/Redis/JDBC) vs own | leaning Bucket4j + JDBC default for clusters | dynamic-runtime-designer | LLD-04/10 | OPEN |
| OQ-08 | Admin UI framework (React/Vite vs Vaadin vs htmx+Thymeleaf) | leaning React+Vite prebuilt into JAR; review components as Web Components (OQ-15) | control-plane-designer | LLD-08 | OPEN |
| OQ-09 | Which IdPs/auth styles do the target teams actually use? | design supports OIDC/JWT/LDAP/SAML/custom (SEC-01 §2) | user | SEC-01 | DEFERRED |
| OQ-10 | Which DBs must v1 support? | design is vendor-portable (LLD-09 §3) | user | LLD-09 | DEFERRED |
| OQ-11 | Approved LLM providers; on-prem requirement? | ModelRouter port supports any Spring AI provider | user | LLD-06 | DEFERRED |
| OQ-12 | Write capability in v1? | **Yes — only via user-reviewed change proposals rendered in UI components, explicit confirmation, applied through host write paths so existing versioning/audit/identity flows record it** | user | scope | RESOLVED → ADR-0009, LLD-11 |
| OQ-13 | Distribution: internal Nexus/Artifactory or Maven Central (OSS)? | — | user | release | OPEN |
| OQ-14 | Which versioning mechanisms do host apps use (Envers, custom history tables, triggers, temporal tables)? | LLD-11 §5 supports all via `VersioningAdapter`; need inventory to prioritize | user | LLD-11 | DEFERRED (with OQ-09/10) |
| OQ-15 | Review/display UI components: framework-agnostic Web Components vs React component library | leaning Web Components (embeddable in any host UI) | control-plane-designer | LLD-11 §7 | OPEN |
| OQ-16 | Allow ENTITY_WRITE (JPA write without a host service method) in v1, or HOST_OPERATION only? | leaning HOST_OPERATION only in v1; ENTITY_WRITE behind flag + approval | user | LLD-11 §4 | OPEN |
| OQ-17 | Align runtime namespaces (`dynamic.ai.agent.*`, `dai_*`, `/dynamic-ai`) with product name before first release? | decide before 1.0 (breaking later) | user | ADR-0010 | OPEN |
| OQ-18 | Require step-up / recent authentication for confirming writes on sensitive data? | configurable per classification; default off | access-management-architect | LLD-11 §6 | OPEN |
| OQ-19 | Unknown environment tier: treat as PROD (current design) or refuse to start until `environment.tier` is set? | treat as PROD + clear log | user | LLD-12 §2.1 | OPEN |
| OQ-20 | Should unannotated attributes of an `@AiContext` entity stay hidden (current) or be exposed with a generated meaning? | hidden (explicit meaning required) | user | LLD-02 §4 | OPEN |
| OQ-21 | Hibernate write-veto `Integrator` on by default (current) or opt-in? It is registered in the host's SessionFactory but is a no-op outside AI scope | on by default | lld-chief-architect | ADR-0014 | OPEN |
| OQ-22 | MCP default mode: stateful Streamable HTTP (notifications, needs sticky sessions) or STATELESS (any replica, clients re-list tools)? | stateful default, stateless for multi-replica hosts | user | LLD-07 §5.1 | OPEN |
| OQ-23 | Stream resumption (`Last-Event-ID`) in v1 or v1.x? | v1.x; v1 cancels on disconnect | user | LLD-13 §7 | OPEN |
| OQ-24 | Build the stdio→HTTP bridge CLI for STDIO-only MCP clients? | v1.x, only if target clients need it | user | ADR-0016 | OPEN |
| OQ-25 | Default `tools.max-parallel-per-turn` (4)? | 4; per-agent override | lld-chief-architect | LLD-14 §5 | OPEN |
| OQ-26 | `UNBOUNDED_LIST_ACTION`: exclude by default or only warn (exclude with `strict`)? | warn by default, exclude in strict/CI | user | LLD-14 §3.3 | OPEN |
| OQ-27 | Which workspaces/regulations need audit evidence mode (GDPR, HIPAA, PCI, SOX)? | opt-in per workspace | user | ADR-0018 | DEFERRED (with OQ-09/10/11) |
| OQ-28 | Propagate `traceparent` to external LLM providers? | off (internal endpoints on) | access-management-architect | LLD-10 §3 | OPEN |
