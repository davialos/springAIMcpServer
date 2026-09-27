# Platform Research Notes (verified 2026-09-27)

Facts the design depends on. Re-verify before implementation starts.

## Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 25 LTS | Virtual threads (final since 21), Scoped Values (final, JEP 506), Structured Concurrency (preview), FFM API (final since 22), Markdown doc comments `///` (JEP 467, Java 23). Java 26 adds primitive types in patterns (JEP 530, still preview) — **do not depend on preview features** in a library. |
| Spring Boot | 4.1.x (4.1.0 GA 2026-06-10; 4.1.1 2026-08-21) | Java 17 min, supports up to 26. Spring Framework 7, **Jackson 3** (`tools.jackson.*`), modularized auto-configure jars, HTTP-client SSRF mitigation. |
| Spring Security | 7.1.x | Lambda DSL only; `AuthorizationManager` based authz. |
| Spring AI | 2.0.x (GA 2026-06-12; docs at 2.0.1) | Boot 4.0/4.1 baseline, JSpecify null-safety. |
| MCP Java SDK | 2.0.0 | Spec 2025-11-25; Streamable HTTP default transport; WebMVC/WebFlux transports now in Spring AI. |

## Spring AI 2.0 tool calling — what we design against

- `org.springframework.ai.tool.ToolCallback` — `getToolDefinition()`, `getToolMetadata()`,
  `call(String input)`, `call(String input, ToolContext ctx)`.
- `ToolDefinition` (name, description, inputSchema JSON). `ToolDefinitions.from(Method)`.
- `MethodToolCallback.builder().toolDefinition(..).toolMethod(..).toolObject(..)` — the
  primitive for **wrapping host bean methods discovered at runtime** (our core need).
- `FunctionToolCallback` — lambda-based tools (used for query-backed tools).
- `ToolContext` — side-channel data **never sent to the model** (principal, tenant, trace).
- `ToolCallingManager` — enforces limits (default 40 calls/tool, 150/turn; configurable).
- `ToolCallingAdvisor` — auto-registered in `ChatClient`, owns the call→tool→resubmit loop
  at order `HIGHEST_PRECEDENCE + 300`; exactly one tool advisor per chain.
- Memory advisor ordering: `+200` = outside loop (sees final messages only); `>+300` = inside loop.
- `ToolSearchToolCallingAdvisor` — progressive disclosure for large tool sets (hundreds).
- `StructuredOutputValidationAdvisor` — re-prompts on non-conforming JSON.
- `ToolExecutionExceptionProcessor` — runtime errors → message to model; property
  `spring.ai.tools.throw-exception-on-error`.
- User-controlled loop: `AdvisorParams.toolCallingAdvisorAutoRegister(false)` — we need
  this for the **propose → review → confirm** write flow (LLD-11).
- **Correction to the original brief:** the brief says `FunctionCallback`; that is Spring AI
  1.0-milestone vocabulary. The 2.x design uses `ToolCallback`/`MethodToolCallback`.

## MCP
- `@McpTool`, `@McpResource`, `@McpPrompt` annotations are in Spring AI 2.0.
- Server auth: OAuth2 / API-key via the community `mcp-security` project — evaluate
  maturity before depending on it (OQ-05).
- Server transports (Spring AI 2.0 boot starter): `spring.ai.mcp.server.stdio=true`; `spring.ai.mcp.server.protocol=SSE` (**deprecated since 2.0.0**) | `STREAMABLE` | `STATELESS`; WebMVC and WebFlux variants; default endpoint `POST /mcp`.
- HTTP transports are **unauthenticated by default** — any client reaching the endpoint can list and call every tool. We must secure it (LLD-07 §5.3).
- MCP authorization spec 2025-11-25: servers MUST publish OAuth Protected Resource Metadata (RFC 9728) and answer 401 with `WWW-Authenticate: … resource_metadata=`; client registration priority: pre-registration → Client ID Metadata Documents (CIMD) → DCR.

## Spring MVC dynamic mappings
- `RequestMappingHandlerMapping.registerMapping(RequestMappingInfo, Object handler, Method)`
  and `unregisterMapping(RequestMappingInfo)` remain the supported runtime-registration API
  in Framework 7. `RequestMappingInfo.paths(..).methods(..).options(builderConfig)` must be
  built with the handler mapping's `BuilderConfiguration` so path matching matches the host.
- WebFlux hosts need a separate adapter (out of scope for v1 — OQ-01).

## Javadoc at runtime (superseded — ADR-0013 uses runtime annotations; kept for the optional enricher)
- javac drops comments; capture via annotation processor: `Elements.getDocComment(Element)`
  returns both `/** */` and `///` comments.
- Alternative: `therapi-runtime-javadoc` (stores per-class JSON resources). See ADR-0003.

## Sources
- https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- https://aaronparecki.com/2025/11/25/1/mcp-authorization-spec-update
- https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- https://docs.spring.io/spring-ai/reference/api/tools.html
- https://www.infoq.com/news/2026/06/spring-boot-4-1/
- https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/
