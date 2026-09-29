# LLD-07: Tool Bridge & MCP

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | agent-runtime-designer |
| Module(s) | `ai` (bridge), `mcp` (server/client) |
| Related features | F-41, F-45, F-55, F-56 |
| Related ADRs | ADR-0008, ADR-0009 |

## 1. Purpose & responsibilities
Convert allow-listed host capabilities into Spring AI `ToolCallback`s that execute
**as the calling principal**, and optionally expose them over MCP / consume external MCP tools.

## 2. Tool binding model
```java
// design sketch
public record ToolBinding(ResourceId id, int revision, WorkspaceId workspace,
        String toolName,                    // ^[a-z][a-z0-9_]{2,63}$, unique per agent
        ToolSource source,                  // sealed: OperationSource(opRef) | QuerySource(queryId) | McpSource(serverId, remoteTool) | AgentSource(agentId, v2)
        String descriptionOverride,         // null ⇒ effective catalog description (@AiExposedAction.intent + policy layers)
        Map<String, ArgConstraint> argConstraints,  // e.g. customerId must equal principal.customerId, max ranges
        WriteMode writeMode,                // READ (execute) | PROPOSE; mutating sources are always PROPOSE (LLD-11), never direct write
        boolean returnDirect,
        Duration timeout, int maxCallsPerTurn,
        ResultPolicy result) {}             // maxChars, masking, summarization
```

## 3. Building a ToolCallback (per request, from cached definitions)
```
OperationSource:
  def = ToolDefinition.builder().name(toolName)
          .description(truncate(override ?? catalogOp.description, 1024) + paramDocs)
          .inputSchema(catalogOp.paramsJsonSchema ∧ argConstraints)   // constraints narrow schema (enum/min/max)
  callback = SecuredToolCallback(
      delegate = MethodToolCallback.builder()
                   .toolDefinition(def)
                   .toolMethod(interfaceMethod)                     // from ResolvedInvocationTarget
                   .toolObject(applicationContext.getBean(beanName)) // PROXY ⇒ @PreAuthorize/@Transactional apply
                   .build(),
      guards)
QuerySource:
  FunctionToolCallback.builder(toolName, (Map args, ToolContext ctx) -> queryExecutor.run(queryId, args, ctx.principal))
      .inputSchema(query.params schema) ...
```

`SecuredToolCallback.call(input, toolContext)` (decorator, our code):
1. Read `InvocationContext` from `ToolContext` (never from model input).
2. Re-check `perm:tool:invoke` for principal on this binding (grants may have changed mid-conversation).
3. Parse input JSON, validate against schema + `argConstraints` (e.g. `principal`-bound args
   are **overwritten** server-side, not trusted from the model).
4. Enforce per-tool call count & timeout (virtual thread + `Future.get(timeout)`).
5. If `writeMode == PROPOSE` → do NOT call the delegate; build a `ChangeProposal` (before-snapshot via
   query engine + `VersioningAdapter` version token) and return `{status: PROPOSED, proposalId}` (LLD-11).
   The delegate runs later only from the confirm API, as the confirming user.
6. Run delegate with Spring `SecurityContext` of the caller set on the executing thread.
7. Post-process result: mask sensitive/classified fields, truncate to `maxChars`, wrap in the
   **tool result envelope** (§3a) — data, never instructions.
8. Audit `TOOL_INVOKED` (args hash, outcome, duration), metrics.

## 3a. Tool result envelope (what the model sees)
Every tool result — host action, query, MCP-remote, agent-as-tool — uses one structured shape, so the
model can tell "nothing matched" from "not allowed" from "try differently", and self-correct:
```json
{ "tool": "find_customer_orders",
  "status": "empty",                         // ok | empty | truncated | error | not_permitted | unavailable | proposed
  "entity": "Order",                         // LOGICAL name from the catalog — never the physical table name
  "applied": { "filters": { "customerId": 4711, "status": "OPEN" }, "limit": 50 },
  "count": 0,
  "data": [],
  "hints": [ "No OPEN orders for this customer. Tool `find_customer_orders` also accepts status=ANY.",
             "Related action: `get_customer_by_email` if the customer id may be wrong." ],
  "truncated": false,
  "page": { "limit": 50, "hasMore": false, "nextCursor": null } }   // opaque HMAC-signed cursor, LLD-14 §3.3
```
Rules:
- `hints` are **generated deterministically** from the catalog (sibling actions on the same entity, their
  `keywords`, enum values of the filtered attribute) — never by an LLM, never containing data.
- `applied.filters` echo only non-sensitive parameters; sensitive params (`@AiParam(sensitive=true)`) appear as `"***"`.
- **No existence oracle:** when row-level security or a mandatory filter removed rows, the result is the
  same `status: empty` as a genuinely empty result. Only an authorization failure on the *tool itself* is
  `not_permitted`. The model (and through it the user) cannot learn that hidden rows exist.
- `error` carries a stable `code` + safe `message` (e.g. `invalid_argument: date must be ISO-8601`), never a stack trace or SQL.
- `truncated: true` whenever the row cap (LLD-05 §5a) or `result-max-chars` cut the data.

**Recording (F-72, implemented).** `ToolBridge` builds each turn's callbacks with a `ToolCallScope` (channel plus turn id, or MCP request id) fixed at build time, so nothing depends on thread-local or scoped state reaching the thread that runs a tool. `SecuredToolCallback` reports every call it handles, whatever the outcome (OK, EMPTY, TRUNCATED, ERROR, NOT_PERMITTED, PROPOSED, call-limit, write-guard veto) to a `ToolCallRecorder`. The record carries hashes of the arguments and of the result, never the values. `StoreToolCallRecorder` writes it to `dai_tool_invocation` off the request path (bounded virtual-thread bulkhead; a full bulkhead or failed write drops and counts the record). Recording failures never change what the model receives. Gaps are tracked in OQ-43.

## 4. Why proxies & runs-as-caller (ADR-0008)
An LLM is an untrusted planner. If tools ran with a service identity, any user could
reach any data the service can (confused deputy). Therefore: caller's `Authentication`
is the execution identity; host method security remains authoritative; our grants are an
**additional** gate, never a bypass.

## 4a. Read-only enforcement for AI tool calls (ADR-0014)
For `readOnly=true` actions, `SecuredToolCallback` runs the delegate as:
```
ScopedValue.where(INVOCATION, ctx.withMode(AI_READ)).call(() ->
    readOnlyTxTemplate.execute(status -> delegate.call(input, toolContext)))
```
- `readOnlyTxTemplate` = `TransactionTemplate(readOnly=true, timeout=binding.timeout)` on the transaction manager
  that owns the target entity's EMF.
- Hibernate `AiWriteGuardIntegrator` vetoes any insert/update/delete/collection change while the scope is `AI_READ`.
- `AiWriteViolationException` → tool result envelope `status: not_permitted` (§3a) to the model, audit `AI_WRITE_VIOLATION`
  (tool, principal, entity), counter `dynamic.ai.agent.tool.write_violations`; after
  `write-guard.auto-disable-after` violations (default 3 per 10 min) the tool is disabled via the kill-switch layer.
- `readOnly=false` actions never reach this path — they are proposal-only (§3 step 5, LLD-11).
- Not an AOP aspect: enforcement lives only on the AI path, so ordinary host traffic is untouched (ADR-0014 context).

## 5. MCP server (F-55, v1.0 behind `dynamic.ai.agent.mcp.server.enabled`, default off) — ADR-0016
### 5.1 Transport
| Transport | Support | Why |
|-----------|---------|-----|
| **Streamable HTTP, stateful** (`spring.ai.mcp.server.protocol=STREAMABLE`) at `{base}/mcp` | Option (`mode=stateful`) | Current MCP transport (replaces HTTP+SSE); adds live `tools/list_changed` push, at the cost of needing sticky sessions or a shared session store across replicas — choose this only when that trade-off is wanted |
| **Stateless** (`protocol=STATELESS`) | **Default** (`dynamic.ai.agent.mcp.server.mode=stateless`, ADR-0021) | No session state ⇒ any replica can serve any request on a plain round-robin load balancer, no sticky sessions, no shared session store — the scalable default for horizontally-scaled hosts. Loses server→client notifications (`tools/list_changed`) — clients re-list tools |
| Legacy HTTP+SSE (`protocol=SSE`) | Off; opt-in for old clients only | Deprecated since Spring AI 2.0 |
| STDIO | **Not in the embedded library** | STDIO means the MCP client spawns the server as a subprocess; our server lives inside a running web application. Desktop clients that only speak STDIO use a small **stdio→HTTP bridge** (separate `mcp-stdio-bridge` CLI, v1.x) that forwards to `{base}/mcp` with the user's token |
Endpoint path and port are the host's (`server.port` + `{base}/mcp`), never a second listener.

### 5.2 Tools, resources, prompts
- Tools exposed = published tool bindings with `mcpExposed=true` ∩ caller's grants ∩ token scopes (§5.4),
  computed per session (stateful) or per request (stateless). Registered programmatically from the effective
  catalog — not static `@McpTool` annotations (ours are runtime-configured). On snapshot change → `tools/list_changed`
  notification (stateful mode).
- **Naming:** `^[a-z][a-z0-9_]{2,63}$` (snake_case, ≤ 64 chars) for every tool, in both the agent runtime and MCP —
  no dots, spaces, brackets, or camelCase. Names are stable (`@AiExposedAction.name`, LLD-02) because evals,
  audit, and client configurations key on them. Collisions across workspaces are resolved by a workspace prefix
  (`sales_find_orders`) only when the same MCP endpoint serves several workspaces.
- Agents may be exposed as one tool each, `ask_<agent_slug>`.
- Tool results use the envelope (§3a). Writes over MCP follow LLD-11: the tool returns `status: proposed` with a
  review URL; the user confirms in the host UI — an MCP client can never confirm a proposal.
- Resources (v1.x): read-only catalog descriptions of published entities (`dai://entities/{name}`), filtered like tools. No data rows as resources.

### 5.3 Authentication — MCP authorization spec (2025-11-25)
- Our MCP endpoint is an **OAuth 2.1 protected resource** (Spring Security resource server):
  - publishes **Protected Resource Metadata** (RFC 9728) at `/.well-known/oauth-protected-resource{base}/mcp`, pointing to
    the host's existing authorization server (Entra ID, Okta, Keycloak…) — we never become an authorization server;
  - on a missing or invalid token: `401` + `WWW-Authenticate: Bearer resource_metadata="…"`;
  - validates **audience** = our MCP resource URI (RFC 8707 resource indicators) — tokens minted for other APIs are rejected;
  - **no token passthrough**: the MCP token is never forwarded to downstream services or external MCP servers.
- Client registration is the authorization server's concern; the recommended order per the spec is pre-registration,
  then Client ID Metadata Documents (CIMD), then Dynamic Client Registration. The framework adds an **approved MCP client
  registry** per workspace: allowed `client_id`s (e.g. the company's internal agent host, approved desktop clients); tokens
  from other clients get `403`. First use by a user of a new approved client records a consent entry (audited, revocable in the dashboard).
- Service accounts may use our API keys (SEC-01 §9) for server-to-server MCP; same scope model.
- Built on Spring Security directly; the community `mcp-security` project is evaluated for PRM/CIMD helpers (OQ-05) but not required.

### 5.4 Scopes and privilege escalation
| Scope | Allows |
|-------|--------|
| `dai.mcp.read` | list tools; call `readOnly=true` tools |
| `dai.mcp.propose` | call write tools, which only create proposals (LLD-11) |
| `dai.mcp.agents` | call `ask_<agent>` tools |
- Effective permission = token scopes ∩ the user's grants (SEC-01 §7) ∩ the tool's requirement, **checked on every
  tool call in `SecuredToolCallback`**, not only at the HTTP layer. Tool calls needing a scope the token lacks get a
  `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="dai.mcp.propose"` (step-up per the spec).
  Scopes never accumulate server-side across calls or sessions; each request stands on its own token.
- No tool can grant, broaden, or modify permissions (no admin operations exist in the MCP surface).

### 5.5 Transport hardening
Validate `Origin` (DNS-rebinding protection); `Mcp-Session-Id` = unguessable (≥ 128-bit) and bound to the token subject —
a session ID presented with a different user's token is rejected; per-session and per-client rate limits;
request-size cap; session idle timeout; the framework never opens URLs or runs shell commands on the model's behalf,
and URLs in tool output are data only.

## 6. MCP client (F-56, v1.x — schema ships in v1.0, feature later)
- `McpServerRegistration{id, url, authMode(NONE|OAUTH_CLIENT_CREDENTIALS|API_KEY via secret ref), allowedTools[], timeout}` — admin-only, approval required.
- URL allow-list + SSRF guard (deny private ranges unless explicitly allowed; Boot 4.1
  HTTP-client SSRF mitigation where applicable). Secrets stored as references to host
  secret manager (`SecretResolver` SPI), never in `dai_*` plaintext.
- Remote tool descriptions are **untrusted** (tool poisoning): shown to approver at
  registration, pinned by hash; changes require re-approval.

## 7. Failure modes
| Failure | Behavior |
|---------|----------|
| Host method `AccessDeniedException` | Model receives "not permitted"; audit `TOOL_DENIED` |
| Bean no longer resolvable | Binding suspended (drift) |
| MCP server down | Tool returns "unavailable"; breaker opens; agent continues |
| Tool output huge | Truncated + note to model |

## 8. Configuration
| Property | Default |
|----------|---------|
| `dynamic.ai.agent.tools.description-max-chars` | `1024` |
| `dynamic.ai.agent.tools.result-max-chars` | `8000` |
| `dynamic.ai.agent.tools.default-timeout` | `15s` |
| `dynamic.ai.agent.mcp.server.enabled` | `false` |
| `dynamic.ai.agent.mcp.client.allowed-hosts` | `[]` |

## 9. Test strategy
Tests proving runs-as-caller (tool calls a `@PreAuthorize` method; user without role
denied even though agent allows the tool); arg-constraint override tests; MCP
conformance tests using the SDK's test client.
