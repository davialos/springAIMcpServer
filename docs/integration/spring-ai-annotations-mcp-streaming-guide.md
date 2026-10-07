# Spring AI, our `@Ai*` annotations, the MCP server and event streaming — how it all fits

Audience: developers who know Spring Boot but not Spring AI, and who need to understand what this library
builds on, what it adds, and what it deliberately does not use.

Status of facts: verified against the code on 2026-10-06 (Spring AI 2.0.1, MCP spec 2025-11-25). Where the design
documents (LLDs) describe something the code does not do yet, this guide says **not implemented** and names the
open question. Design sources: LLD-02, LLD-06, LLD-07, LLD-13, LLD-17, ADR-0008, ADR-0013, ADR-0014, ADR-0015,
ADR-0016, ADR-0017, `docs/research/spring-ai-2-notes.md`, `docs/open-questions.md`.

---

## 1. The one-minute picture

```
 Host application code                 This library                           Outside world
 ─────────────────────                 ────────────                           ─────────────
 @AiContext  @AiEntityProperty         1. Startup scan  ──► EffectiveCatalog
 @AiExposedAction  @AiParam               (annotations + JPA metamodel         (immutable snapshot,
 @AiQueryConstraints                       + policy JSON + dashboard)           what the AI may see)
                                                    │
                                                    ▼
                                       2. Tool bindings (published config)
                                          binding = catalog operation / query / agent / criteria
                                                    │
                                                    ▼
                                       3. ToolBridge ─► SecuredToolCallback ─► host bean (via Spring proxy)
                                          (a Spring AI ToolCallback,            runs AS THE CALLER
                                           wrapped with our security)
                                                    │
                          ┌─────────────────────────┴──────────────────────────┐
                          ▼                                                    ▼
              4a. Agent runtime (Spring AI ChatClient)             4b. MCP server (own implementation)
                  advisors + tool loop + memory                        POST /dynamic-ai/mcp, JSON-RPC
                  sync or streaming                                    tools/list, tools/call
                          │                                                    │
                          ▼                                                    ▼
              5. SSE stream  POST …/chat/stream                    Cursor, Claude Desktop, IDEs, agents
                 typed events (dai-stream/1)                       (OAuth 2.1 bearer or API key)
                          │
                          ▼
                 Browser chat UI / host UI
```

Three ideas carry the whole design:

1. **Annotations are the only way to expose anything.** Nothing is visible to a model unless a developer annotated
   it (default deny). Configuration can only *restrict or re-describe*, never expose (ADR-0013).
2. **Spring AI is the execution engine, we are the governor.** Spring AI runs the model call and the
   call → tool → resubmit loop. Everything that decides *whether and as whom* a tool runs is ours
   (`SecuredToolCallback`, grants, read-only scope, argument constraints, redaction, budgets).
3. **Agents and MCP share one secured tool path.** An agent chat turn and an MCP `tools/call` both end in the same
   `SecuredToolCallback`, so a rule fixed once holds on both surfaces (ADR-0008).

---

## 2. Spring AI primer (what you need to know before reading the rest)

| Spring AI concept | What it is | Where we meet it |
|---|---|---|
| `ChatModel` | Provider-specific model client (OpenAI, Anthropic, …). Provided by the host through a Spring AI starter | `ModelRouter` resolves one per agent; `ResilientChatModel` wraps it |
| `ChatClient` | Fluent API on top of `ChatModel`: `prompt().user(..).tools(..).advisors(..).call()` / `.stream()` | Built per turn in `DefaultAgentInvoker.buildChatClient` |
| `Advisor` (`CallAdvisor`, `StreamAdvisor`) | Interceptor around the model call. Ordered by `getOrder()`; can mutate request/response or abort | Our guard, knowledge, summary-memory, structured-output and metering advisors |
| `ToolCallback` | A callable tool: `ToolDefinition` (name, description, JSON input schema) + `call(String json, ToolContext)` | Every tool we expose is a `ToolCallback` |
| `ToolContext` | Side-channel map handed to a tool, **never sent to the model** | We carry the caller identity there — never trust identity from model arguments |
| `ToolCallingAdvisor` | Spring AI's built-in advisor that runs the tool loop. Auto-registered at `HIGHEST_PRECEDENCE + 300` | Present implicitly; our advisors are ordered around it |
| `ChatMemory` / `ChatMemoryRepository` | Conversation history store keyed by a conversation id | `MessageChatMemoryAdvisor` + our PostgreSQL-backed repository |
| `EmbeddingModel` | Text → vector | Optional, used only to embed bundled knowledge packs |
| Observation (Micrometer) | Spring AI emits `gen_ai.*` spans/metrics | We pass the host's `ObservationRegistry` so model spans nest under our `dai.agent.turn` span |

Mental model of one turn:

```
user text ─► [advisor chain, outer → inner] ─► ChatModel ─► tool call requested?
                                                    ▲              │ yes
                                                    └── tool result ◄── ToolCallback.call()
                                                         (loop, owned by ToolCallingAdvisor)
final text ◄─ [advisor chain, inner → outer] ◄──────────┘ no
```

Naming note: `FunctionCallback` is Spring AI 1.x vocabulary. This codebase uses the 2.x names (`ToolCallback`,
`ToolCallingAdvisor`).

---

## 3. Our annotations and what each one turns into

All six annotations live in the dependency-free `annotations` module (`RUNTIME` retention, no Spring types), so
host domain/API jars can use them.

| Annotation | Target | What the developer says | What the library builds from it |
|---|---|---|---|
| `@AiContext(description, keywords, name, classification)` | JPA entity, service class | Plain-English meaning of the thing, for the model | Catalog **entity** (queryable once published) or a **context** description for a bean's actions |
| `@AiEntityProperty(meaning, sensitive, writable, classification)` | field / record component / getter | What the column means; whether it must never reach a model | Catalog **attribute**. Unannotated attributes of an annotated entity stay hidden. `sensitive=true` ⇒ never exposed, masked everywhere |
| `@AiExposedAction(intent, readOnly, name, idempotent, keywords)` | public method on a Spring bean | What the method accomplishes | Catalog **operation** ⇒ *candidate* tool. `readOnly=true` (default) can execute; `readOnly=false` is **proposal-only** |
| `@AiParam(description, name, required, sensitive)` | method parameter | What the argument is; whether to redact it in traces | Field in the tool's JSON input schema; redaction in audit/args echo |
| `@AiQueryConstraints(maxLimit, mandatoryFilters)` | entity | Row cap and server-bound filters for dynamic queries | Limits enforced by the query engine, e.g. a tenant filter the model cannot drop |
| `@AiRowContext(label, maxChars)` | text field/getter (also `@AiEntityProperty`) | A note about *that particular record* (remarks, history) | Value travels with each returned row under `_context`, even if not selected; governance (sensitive/classification) still applies; treated as data, cut at `maxChars` |
| `Classification` (enum) | attribute of the above | `PUBLIC … RESTRICTED` | Drives ABAC decisions, masking and model-provider eligibility |

### 3.1 From annotation to tool: the pipeline

1. **Scan once at startup** (`SmartInitializingSingleton`, after all singletons exist). Sources: bean definitions
   (without instantiating lazy beans), only under `dynamic.ai.agent.scan.base-packages` (default: the
   `@SpringBootApplication` package), and the JPA metamodel for entities. Annotations on interfaces are found.
   Methods need parameter names (`-parameters` or `@AiParam(name)`), otherwise the action is excluded with a scan issue.
2. **Merge policy layers** into an immutable `EffectiveCatalog`: annotations → classpath/file JSON → dashboard
   overlays → kill switches. Layers can disable, restrict or re-describe; they cannot add an element code did not
   annotate.
3. **Publish tool bindings.** An admin publishes a `TOOL_BINDING` that points an agent (or MCP) at a catalog
   element: `operation`, `query`, `agent`, `criteria` (model-built read queries) — `mcp` bindings parse but are not
   executed yet. The binding adds `argConstraints`, timeouts, result limits and `mcpExposed`.
4. **Build callbacks per request** (`ToolBridge.buildCallbacks`): delegate callback for the source, wrapped in
   `SecuredToolCallback`.
5. **Execute** (next section).

The annotation text is what the model reads when choosing tools, so `intent`, `meaning` and `description` are
prompt engineering: short, concrete, domain words. Limits: description/intent ≤ 1 024 chars, meaning ≤ 256.

Rules the scanner enforces for you (examples): list-returning actions without a bound are flagged
`UNBOUNDED_LIST_ACTION`; duplicate tool names are both excluded; sensitive-sounding attribute names
(`password`, `token`, `iban`, …) with `sensitive=false` are excluded unless explicitly confirmed;
controllers get `@AiContext` for description only and are never tools — annotate the service method instead.

### 3.2 Example

```java
@Entity
@AiContext(description = "A customer purchase order", keywords = {"order", "purchase"})
@AiQueryConstraints(maxLimit = 50, mandatoryFilters = {"customerId"})
public class Order {
    @AiEntityProperty(meaning = "Business order number shown to the customer")
    private String number;
    @AiEntityProperty(meaning = "Lifecycle state: OPEN, SHIPPED, CANCELLED")
    private String status;
    @AiEntityProperty(meaning = "Card number used to pay", sensitive = true)   // never reaches a model
    private String cardNumber;
}

@Service
@AiContext(description = "Order lookups and changes")
public class OrderService {
    @AiExposedAction(intent = "Find a customer's recent orders, newest first")
    public Page<OrderView> findRecentOrders(
            @AiParam(description = "Customer id") Long customerId, Pageable page) { … }

    @AiExposedAction(intent = "Cancel an order", readOnly = false)   // proposal-only: a human confirms
    public void cancelOrder(@AiParam(description = "Order number") String number) { … }
}
```

---

## 4. How a tool call runs (the core of the Spring AI integration)

`SecuredToolCallback` implements Spring AI's `ToolCallback` as a decorator. Spring AI sees a normal tool; inside,
our guards run first.

| Step | What happens | Why |
|---|---|---|
| 1 | Read the `InvocationContext` from `ToolContext` | Identity never comes from model-generated JSON (prompt-injection defence) |
| 2 | Re-check the caller's `tool:invoke` grant for this binding | Grants may change mid-conversation |
| 3 | Parse JSON args, apply `argConstraints` | `principalAttr` and `literal` constraints **overwrite** what the model sent; a `range` violation refuses the call |
| 4 | Enforce per-tool call count and timeout | Bounded runaway loops (virtual thread + deadline) |
| 5 | `writeMode == PROPOSE` ⇒ do **not** call the host method; create a `ChangeProposal`, return `status: proposed` | The model never performs a write (ADR-0009). A human confirms in the host UI |
| 6 | Run the delegate under the caller's Spring `SecurityContext`, through the **proxied** bean | Host `@PreAuthorize`, `@Transactional`, validation keep working (ADR-0008) |
| 7 | For read tools run inside `AiReadScope` (a `ScopedValue`) and a read-only transaction | Read-only enforcement in depth (ADR-0014): Hibernate write veto, `Connection.setReadOnly` |
| 8 | Shape the result as the **tool result envelope** | See below |
| 9 | Record the call (hashes of args/result, never values) | Audit / `dai_tool_invocation` |

The **envelope** the model sees is always the same shape: `status` (`ok | empty | truncated | error |
not_permitted | unavailable | proposed`), logical entity name, applied filters, `count`, `data`, deterministic
`hints` generated from the catalog, and a signed pagination cursor. Rows removed by row-level rules look exactly like
an empty result (no existence oracle). Errors carry a stable code and a safe message — never stack traces or SQL.

Delegate callbacks are our own `ToolCallback` implementations (`BackingToolCallback` for operation/query bindings,
`CriteriaToolCallback` for the three model-built query tools `describe_data_model`, `check_data_query`,
`run_data_query`, `AgentDelegateToolCallback` for agent-as-tool). They reuse the same backing code as the dynamic REST
endpoints rather than Spring AI's reflective `MethodToolCallback`, so results are shaped uniformly.

---

## 5. Spring AI features — what we use and what we do not

### 5.1 Used

| Spring AI feature | Where | Notes |
|---|---|---|
| `ChatClient` (+ `ChatClient.Builder`) | `DefaultAgentInvoker` | Built per turn: system prompt, default options (model, temperature, max tokens), advisors; `.call()` for sync, `.stream()` for SSE |
| `ChatModel` | `ModelRouter`, `DefaultModelRouter`, `ResilientChatModel` | Host supplies `ChatModel` beans; we route per agent, with fallback chain and per-provider circuit breaker (`ProviderBreaker`). 429s are not breaker failures |
| `ChatOptions` / `ToolCallingChatOptions` | `chatOptions(..)` | Per-agent model name, temperature, max tokens |
| `Prompt`, `UserMessage`, `SystemMessage`, `AssistantMessage`, `ChatResponse`, `Generation`, `Usage` | runtime, metering | Token usage read from `ChatResponse` metadata (input/output tokens) for budgets and cost |
| `Advisor`, `CallAdvisor`, `StreamAdvisor`, `CallAdvisorChain`, `StreamAdvisorChain`, `ChatClientRequest/Response` | `ai.advisor`, `ai.knowledge` | Five custom advisors (table 5.3) |
| `MessageChatMemoryAdvisor`, `ChatMemory`, `MessageWindowChatMemory`, `ChatMemoryRepository` | memory strategy `WINDOW`; `StoreChatMemoryRepository` | Memory key = hash(workspace, agent, principal, conversation id), so a user cannot read another's conversation. Persistent (PostgreSQL) or in-heap |
| `ToolCallback`, `ToolDefinition`, `ToolMetadata`, `ToolContext` | `ai.tool`, autoconfigure | Core tool contract (section 4). Caller identity travels in `ToolContext` |
| `ToolCallingAdvisor` (implicit) | Spring AI auto-registers | The tool loop. Our guard/memory/knowledge advisors sit outside it; structured-output and metering at the end of the chain |
| `ToolCallingManager` default limits | Spring AI | Default per-tool/per-turn call caps still apply on top of our `maxToolCallsPerTurn` |
| `EmbeddingModel` | `KnowledgeIndexer`, `ClasspathKnowledgeStore` | Optional. Embeds bundled knowledge packs; without it, search falls back to BM25 only (hybrid when present) |
| `ObservationRegistry` / Micrometer observations | `ChatClient.builder(chatModel, registry, …)` | Spring AI chat/advisor spans nest under our `dai.agent.turn`; stream path passes the parent through the Reactor context |
| Reactor `Flux` from `.stream().chatResponse()` | streaming | Mapped to typed `StreamEvent`s (section 8) |
| JSON schema for tool input | scanner + `ToolDefinition` | Type → schema reuses Spring AI's generator conventions, then decorated with our descriptions and with sensitive members removed |

### 5.2 Not used (and why)

| Spring AI feature | Status |
|---|---|
| `MethodToolCallback` / `@Tool` annotation | Not used for host code. Tools are catalog-driven and runtime-configurable, so we register our own `ToolCallback`s. `@AiExposedAction` plays the role `@Tool` plays in plain Spring AI |
| `FunctionToolCallback` | Not used directly; our callbacks implement the interface |
| `ToolSearchToolCallingAdvisor` (progressive tool disclosure) | Designed (LLD-06 §3, threshold `tool-search-threshold`) but **not implemented** in code |
| Custom `ToolCallingManager` with parallel read calls | Designed (ADR-0017, status Proposed) but **not implemented**: tool calls run through Spring AI's default manager |
| `RetrievalAugmentationAdvisor` / `VectorStore` RAG | Not used. Knowledge is our own `KnowledgeAdvisor` over bundled packs (BM25 + optional embeddings), ACL-aware |
| `ToolExecutionExceptionProcessor` | Not relied on; `SecuredToolCallback` converts failures into the envelope itself |
| Spring AI structured-output converters | Not used; `JSON_SCHEMA` agents use our `StructuredOutputValidationAdvisor` (validates, retries/refuses) |
| Spring AI MCP **server** starter (`spring.ai.mcp.server.*`) | **Not used** — see section 6 |
| Spring AI MCP **client** / `@McpTool` annotations | Not used. MCP client (consuming remote MCP tools) is v1.x; `mcp` bindings are parsed but not executed |
| Provider starters (OpenAI, Anthropic, …) | Not bundled. The host brings its own `ChatModel`/`EmbeddingModel` beans |

### 5.3 Our advisors and their order

Spring AI orders advisors by `getOrder()` (lower runs first on the way in). The tool loop is at
`HIGHEST_PRECEDENCE + 300`.

| Order | Advisor | Job |
|---|---|---|
| `HIGHEST + 200` | `InvocationGuardAdvisor` | Kill switch, input size, blocked patterns, topic allow-list, **prompt validation** (injection/jailbreak detection, optional business-scope check, host `PromptValidator` beans), budget pre-check. Fails closed |
| `HIGHEST + 201` | `MessageChatMemoryAdvisor` or `SummaryMemoryAdvisor` | History injection (strategy `WINDOW` / `SUMMARY`; `NONE` adds nothing). Outside the tool loop, so it records final messages only |
| `HIGHEST + 202` | `KnowledgeAdvisor` | Adds retrieved knowledge-pack passages to the prompt (only if the agent references packs) |
| `HIGHEST + 300` | Spring AI `ToolCallingAdvisor` | Tool loop (implicit) |
| `LOWEST − 100` | `StructuredOutputValidationAdvisor` | Only for `JSON_SCHEMA` agents; validates the answer |
| `LOWEST` | `UsageMeteringAdvisor` | Tokens/cost from `ChatResponse` metadata → budget ledger and Micrometer |

Output guardrails (PII redaction, length cap, structured display tree) run in the invoker on the final answer
(`TurnSafety.OutputGuard`), not as an advisor — streams need the chunk-aware variant (section 8.3).

---

## 6. The MCP server

### 6.1 What it is — and the deliberate deviation from Spring AI

Spring AI ships an MCP server starter and the MCP Java SDK. **We do not use them for the server.** The endpoint
speaks the MCP wire protocol directly (ADR-0016 amendment, 2026-09-29): `McpProtocolHandler` parses one stateless
JSON-RPC 2.0 message per HTTP POST and `McpEndpointController` adds the HTTP checks. Reasons: the per-request
guarantees (caller's Spring Security authentication reaches every tool; tool list computed **per caller** on each
request; every request recorded) are explicit, and the SDK 2.0 server API could not be verified when written. The
handler and controller are split so an SDK-based transport can replace the controller later without touching tool
listing, security or recording (open question OQ-49).

### 6.2 MCP features: implemented vs not

| MCP capability | Status |
|---|---|
| Transport: Streamable HTTP, **stateless**, `POST /dynamic-ai/mcp` | Implemented (default, scale-out friendly: any replica, no sticky sessions) |
| Protocol versions `2025-11-25`, `2025-06-18`, `2025-03-26` | Implemented (`MCP-Protocol-Version` header checked) |
| `initialize` (capabilities: `tools`, `listChanged: false`), `ping` | Implemented |
| `tools/list`, `tools/call` | Implemented. List = bindings with `mcpExposed=true` ∩ caller grants ∩ token scopes; a tool outside the caller's scopes is simply not listed |
| Tool results use the envelope of section 4 | Implemented |
| Writes over MCP | A write tool returns `status: proposed` with a review reference; an MCP client can never confirm a proposal |
| Auth: OAuth 2.1 resource server (bearer), audience-bound, **no token passthrough** | Implemented through the host's Spring Security |
| Auth: API keys for service accounts | Implemented (same scope model) |
| RFC 9728 Protected Resource Metadata (`/.well-known/oauth-protected-resource/dynamic-ai/mcp`) and `401` + `WWW-Authenticate: Bearer resource_metadata=…` | Implemented (challenge needs `mcp.resource-uri` set) |
| `Origin` validation (DNS rebinding), request size cap, approved-client registry (default deny, register → approve by someone else → revoke) | Implemented |
| Scopes `dai.mcp.read`, `dai.mcp.propose`, `dai.mcp.agents` (effective = token scope ∩ user grants ∩ tool requirement, re-checked per call) | Implemented, except the `insufficient_scope` step-up `403` |
| Stateful mode, sessions, `Mcp-Session-Id`, `tools/list_changed` push | **Not implemented** (OQ-49); clients re-list tools |
| Resources, prompts, sampling, logging, batches, server-initiated messages | **Not implemented** (`-32601` / `-32600`) |
| `GET`/`DELETE /dynamic-ai/mcp` | Answered but no server→client stream is offered |
| Legacy HTTP+SSE transport, embedded STDIO | **Not provided** (STDIO-only clients need a bridge, v1.x) |
| Per-session / per-client rate limits | **Not implemented** (OQ-49) |
| MCP **client** (consume remote MCP servers as tools) | Schema only; feature is v1.x |

Request check order, each failing closed: `Origin` (403) → `MCP-Protocol-Version` (400) → body size (413) →
authentication (401 + challenge) → workspace (400; `X-DAI-Workspace` header or `mcp.workspace-id`) → approved
client → method dispatch. Every request is recorded in `dai_mcp_request`, each tool call in `dai_tool_invocation`
under it. Arguments and results are never logged.

### 6.3 Enabling and connecting (short form)

```properties
dynamic.ai.agent.mcp.enabled=true
dynamic.ai.agent.mcp.workspace-id=<workspace uuid>
dynamic.ai.agent.mcp.resource-uri=https://api.example.com/dynamic-ai/mcp
```

Then: publish a tool binding with `mcpExposed=true`, grant the caller `tool:invoke`, register and approve the client,
and point the MCP client at `https://<host>/dynamic-ai/mcp` with a bearer token or API key. The full walkthrough,
including property names and troubleshooting (“tool not in `tools/list`”), is in
[`host-integration-guide.md`](host-integration-guide.md) (MCP section).

---

## 7. How annotations, Spring AI and MCP line up

| Concern | Annotation / catalog | Spring AI part | MCP part |
|---|---|---|---|
| Tool name | `@AiExposedAction.name` (default snake_case of method); pattern `^[a-z][a-z0-9_]{2,63}$` | `ToolDefinition.name` | MCP tool `name` (same string) |
| Tool description | `@AiExposedAction.intent` + overlays | `ToolDefinition.description` | MCP tool `description` |
| Input schema | `@AiParam`, parameter types | `ToolDefinition.inputSchema` | MCP tool `inputSchema` |
| Exposure | Annotation + published binding | Agent's tool list for the turn | `mcpExposed=true` on the binding |
| Who may call | Grants, classification, row rules | Re-checked in `SecuredToolCallback` per call | Token scopes ∩ grants, checked at list time **and** per call |
| Read vs write | `readOnly` | Read tools execute in read-only scope; writes become proposals | Same callback ⇒ same behaviour |
| Sensitive data | `sensitive`, `Classification` | Masked in envelope | Same envelope |
| Identity | Host Spring Security | `ToolContext` + `SecurityContext` | Bearer/API key → `DaiPrincipal` per request |

---

## 8. Event streaming

### 8.1 Transport

Agent turns stream as **Server-Sent Events** on servlet (Spring MVC) hosts (ADR-0015).

```
POST /dynamic-ai/api/agents/{slug}/chat/stream
Accept: text/event-stream     Authorization: Bearer … (or session cookie + CSRF)
{ "conversationId": "…?", "message": "…", "clientRequestId": "uuid" }
```

- **POST, not GET**: prompts must not appear in URLs/logs, and `EventSource` cannot send bearer tokens. Clients use
  `fetch()` with a streamed body.
- The controller (`AgentChatController`) returns `Flux<ServerSentEvent<String>>`; Spring MVC adapts it. `reactor-core`
  arrives with Spring AI, so **no WebFlux host is required**.
- Headers: `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`. Disable proxy buffering and response
  compression on this path; load-balancer idle timeout must exceed the 15 s heartbeat.
- Sync alternative: `POST …/chat` returns the whole answer as JSON.
- Pre-stream failures (authz, validation, rate limit, budget) are normal HTTP problem responses (`application/problem+json`);
  the stream only opens after the checks pass.

### 8.2 Event contract `dai-stream/1` — what is actually emitted

Each event has SSE `id: <turnId>:<seq>` and a JSON `data` with `type`. The sealed `StreamEvent` type defines eleven
events; today the runtime emits these:

| Event | Emitted | Meaning |
|---|---|---|
| `turn.start` | yes | First event: `turnId, conversationId, agent, revision, protocol` |
| `text.delta` | yes | A chunk of answer text, **after** the guardrail window (redaction) |
| `ui.component` (`componentType: "structured-response"`) | yes | Backend-built display tree (text, fields, table, section), sent once before `usage` |
| `usage` | yes | `inputTokens, outputTokens, costMicros, model` |
| `turn.end` | yes | `finishReason` (`stop | length | tool_limit | budget | cancelled`), `messageId` |
| `error` | yes | Terminal failure after `200`: stable `code`, `retryable`, `turnId` — never the exception message |
| `tool.call`, `tool.result` | **defined, not emitted** | Planned tool-activity events |
| `proposal.created/updated/applied` | **defined, not emitted** | Proposals are reviewed via `/dynamic-ai/api/proposals`, with no stream link yet (OQ-48) |

Heartbeats are SSE **comments** (`: keep-alive`, every 15 s), not events, and are bounded with `takeUntilOther`
on the content stream so the response closes when the turn ends.

`dai-stream/2` (LLD-17: surfaces, interrupts, `interaction.end`, `state.snapshot`, one `POST …/interactions` endpoint,
cross-node resume) is **target design**; its schemas are in `docs/schemas/`, phased work in LLD-17 §20.

### 8.3 Streaming behaviours worth knowing

- **Guardrail window.** `StreamingPiiRedactor` holds back up to 128 characters so a value split across chunks
  (card number, e-mail) is caught; text is released at safe whitespace boundaries. Cost: about one chunk of latency.
- **`JSON_SCHEMA` agents** are held back whole, validated when the stream ends, then sent as one text event or refused
  with an error event (OQ-51).
- **Pre-checks run before the stream opens** (kill switch, input size, patterns, topic list, prompt validation,
  budget) and are marked `PRECHECKED` so the stream advisor does not repeat them.
- **Timeouts**: whole-turn timeout (`limits.turnTimeout`, default 60 s) applied to the model flux; provider
  time-to-first-token/idle timeouts are described in LLD-06 §6.1.
- **Cancellation.** Client disconnect → Reactor cancel → model call disposed → in-flight tool work interrupted;
  the partial answer is recorded as cancelled. Duplicate `clientRequestId`s are rejected while one is in flight.
- **Resumption (same node).** `InMemoryTurnEventBuffer` keeps recent events per turn;
  `GET …/turns/{turnId}/events` with `Last-Event-ID: <turnId>:<seq>` replays missed events. Another node answers
  `turn_not_resumable`; cross-node resume is part of `dai-stream/2` design.
- **Tool execution never runs on the Reactor/servlet thread** in the design (bounded virtual-thread executors).
- **Observability.** Time-to-first-token, turn duration, finish reasons; spans `dai.agent.stream` / `dai.agent.turn`.

### 8.4 Streaming and MCP are different channels

| | Agent chat stream | MCP endpoint |
|---|---|---|
| Direction | Server → browser/UI, one response per POST | Request/response JSON-RPC per POST |
| Format | SSE events `dai-stream/1` | JSON-RPC 2.0 results |
| Streaming of tool progress | Planned (`tool.call`/`tool.result`) | None (no server-initiated messages in stateless mode) |
| Auth | Host session or bearer | OAuth 2.1 bearer or API key |

---

## 9. Safety model in one table

| Threat | Mitigation | Reference |
|---|---|---|
| Model asks for data/actions it should not have | Default-deny annotations + grants checked at list time and per call | LLD-02, ADR-0013 |
| Prompt injection via user text | `MaliciousPromptValidator` (weighted deterministic rules), optional business-scope validator | LLD-06 §8.1 |
| Prompt injection via tool output | Results are data in an envelope; tools run as the caller; writes only via human-confirmed proposals | ADR-0008, ADR-0009 |
| Model forges identity in tool args | Identity only from `ToolContext`; principal-bound args overwritten | LLD-07 §3 |
| A "read" tool writes | Read-only transaction + Hibernate write veto; violations audited and auto-disable the tool | ADR-0014 |
| Sensitive data leaves | `sensitive`, classification masking, PII redaction on input/output/stream, `RESTRICTED` ⇒ on-prem providers only | LLD-06 §8, LLD-12 |
| Cost runaway | Budget pre-check + metering, per-turn tool/token/time limits | LLD-06 §9, LLD-10 |
| Provider outage | Fallback chain, per-provider failure-rate circuit breaker | LLD-06 §6 |
| Open MCP endpoint | Origin check, approved-client registry, audience-bound tokens, no passthrough | ADR-0016 |

---

## 10. Where to look in the code

| Topic | Location |
|---|---|
| Annotations | `spring-ai-mcp-server-common-annotations/…/annotations/` |
| Scan, catalog | `core` (model, scanner port), autoconfigure `DaiCoreAutoConfiguration` |
| Agent runtime, `ChatClient` assembly | `ai/runtime/DefaultAgentInvoker.java` (`buildChatClient`, `invoke`, `stream`) |
| Advisors | `ai/advisor/`, `ai/knowledge/KnowledgeAdvisor.java` |
| Tool bridge and guards | `ai/tool/ToolBridge.java`, `SecuredToolCallback.java`, `ToolResultEnvelope.java`, `ai/guard/AiReadScope.java` |
| Delegate callbacks | autoconfigure `BackingToolCallback`, `CriteriaToolCallback`; `ai/tool/AgentDelegateToolCallback` |
| Model routing and resilience | autoconfigure `DefaultModelRouter`, `ResilientChatModel`, `ProviderBreaker` |
| MCP server | `mcp/server/McpProtocolHandler.java`, `McpEndpointController.java`, `DefaultMcpToolsProvider.java`, `McpOriginValidator.java`, `ProtectedResourceMetadata.java` |
| SSE endpoint | `webmvc/endpoint/AgentChatController.java`, `InMemoryTurnEventBuffer.java`; events in `ai/runtime/StreamEvent.java` |
| Design | `docs/lld/02, 06, 07, 13, 17`; `docs/adr/0008, 0009, 0013–0017`; gaps in `docs/open-questions.md` (OQ-48, 49, 50, 51) |

## 11. Known gaps (summary)

Not implemented: `tool.call`/`tool.result`/`proposal.*` stream events; `ToolSearchToolCallingAdvisor`;
parallel-read `ToolCallingManager`; MCP stateful mode, sessions, `list_changed`, resources, prompts, step-up
`insufficient_scope`, rate limits; MCP client; output exfiltration-pattern filtering (OQ-55); `dai-stream/2`.
Each is tracked in the LLDs/open questions named above.
