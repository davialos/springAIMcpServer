# ADR-0027: AI assistant for the rule console through the starter's agent runtime, and an Angular console
- Status: Accepted
- Date: 2026-10-06
- Deciders: product owner (request of 2026-10-06), lld-chief-architect

## Context
ADR-0026 delivered a React console and a rule-engine service. The product owner asked for a complete Angular (TypeScript)
console for the CEL rule setup with an **AI chat** integrated, kept **side by side** with the React console, and for the chat to
**reuse the starter's `/dynamic-ai` agent runtime** instead of a bespoke chat endpoint.

## Decision
- **The rule-engine service embeds the starter** (`spring-ai-mcp-server-common-spring-boot-starter`). Nothing of the library is
  re-implemented: the agent, tool bridge, authorization, rate limits, SSE streaming, memory and the conversation log are the
  starter's. The service's own Flyway still applies V1..V12 (`store.migrate=false`, `validate-schema=true`), so the library's
  persistence unit only uses the schema.
- **Tools** (`list_rules`, `get_rule`, `list_rule_groups`, `get_rule_group`, `list_library_parameters`, `check_cel_expression`)
  are `@AiExposedAction` methods of `com.example.ruleconsole.assistant.RuleSetupTools`, a thin adapter over `AssistantTools`.
  They are **read-only** (ADR-0009: a model never changes data; nothing here evaluates a group either, which would write
  evaluation rows). The adapter lives outside `com.springaimcpservercommon` because the library's catalog scan excludes its own
  package, and returns plain value trees because the library renders tool results with `CanonicalJson`. Tools run **as the
  signed-in user** (ADR-0008): `Caller.current()` reads tenant and organization from the user's token, so the assistant has
  exactly the console's visibility and a model has no parameter with which to name another tenant.
- **Lazy provisioning through the library's own admin API** (`AssistantProvisioner`, `GET /api/v1/assistant`): when a tenant's
  user first opens the assistant, the service creates the tenant's workspace (`tenant_id` = the token's tenant; the log queries
  already scope conversations by it), six `TOOL_BINDING`s and the `AGENT`, each submitted by one provisioning identity and
  approved by another (separation of duties applies to the service too), then the user's membership and the grants
  `agent:invoke` / `tool:invoke`. The provisioning identities are short-lived tokens the service signs with the key it already
  holds (scope `dai.provision`, mapped to `PLATFORM_ADMIN` by the static role mapping); no user token carries that scope. The
  user is registered with the library through **their own** token. Default deny holds: nobody can chat before this, and only
  authenticated users of the tenant get there. Agent and workspace slug = `rule-assistant-` + a hash of the tenant id (the
  library indexes agents by slug across workspaces; a prefix of UUIDv7 tenant ids collides).
- **Models.** The agent names a provider id and the library's router matches a `ChatModel` bean (no silent substitution).
  `offlineChatModel` is always present: a keyword router over the real tool path that phrases answers from templates and says it
  is not an LLM, so the stack, its tests and the streaming pipeline work without a key. `SPRING_AI_MODEL_CHAT=anthropic` +
  `ANTHROPIC_API_KEY` + `ASSISTANT_PROVIDER=anthropic` serve the same agent with Claude (`spring-ai-starter-model-anthropic`,
  inactive by default). If the configured provider has no bean the console says so instead of failing.
- **Security chains.** The host chain is scoped to `/api/**`, `/actuator/**`, `/error`; a lowest-precedence chain denies the
  rest; the library's chains for `/dynamic-ai/**` sit in front. nginx forwards only `/dynamic-ai/api/agents/` (SSE, unbuffered)
  to the browser; the library's admin API is not reachable from the console's origin.
- **Angular console** (`docker/rule-engine/ui-angular`, port 8081, React stays on 8080): standalone components, signals,
  zoneless, strict templates, Angular **21.2** (CLI 22 needs Node ≥ 22.22.3; the sandbox has 22.22.0). Same protobuf contract
  (static code, no `eval`), same tokens/CSS, same strict CSP from the one `ui/security-headers.conf` (no component styles, no
  critical-CSS inlining, no inline script). Pages: login, overview, parameter library, rules (live CEL validation through
  `POST /api/v1/expressions/check`), rule groups (create/edit), triggers & channels (read-only), test bench, administration
  (evaluations, audit trail, **AI conversations with transcripts**) and the assistant panel (fetch + a small SSE parser, because
  `EventSource` cannot POST with a bearer header; answers are rendered as elements from a markdown subset, never as HTML).

## Consequences
- + The assistant is governed like every other agent: grants, audit, rate limits, budget, kill switch and the admin chat log
  work unchanged, and a tenant cannot see another tenant's setup through it.
- + Embedding the starter in a real service found two defects of the library that its own tests could not see (fixed in the
  same change set): a host with Actuator failed to start (two `RequestMappingHandlerMapping` beans), and the starter did not
  carry the `mcp` module that `DaiProperties` needs.
- − The service now carries Hibernate and the Spring AI stack (jar 42 MB → ~100 MB) although the rule engine itself is plain JDBC.
- − Grants are per user, created when the user first opens the assistant; a user created later gets chat access on first use, a
  user whose access an administrator revoked in the library is re-granted on the next first-use after a restart (the in-memory
  "ready" markers are per node). Group-based grants need group principals, which cannot be created up front (OQ-72).
- − Not verified here: a real Anthropic model (no key or egress in the build environment); the in-Docker build stages.
- − The assistant cannot create or change rules. A reviewed-proposal tool (`readOnly=false`, ADR-0009) is the designed next step
  (OQ-72).
