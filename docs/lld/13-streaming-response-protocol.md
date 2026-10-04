# LLD-13: Streaming Response Protocol (SSE)

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | agent-runtime-designer (with control-plane-designer for the client side) |
| Module(s) | `webmvc` (stream endpoint), `ai` (event mapping), `review-ui` (JS client, renderers) |
| Related features | F-42, F-43, F-45, F-51, F-52, F-53 |
| Related ADRs | ADR-0015 |
| Input | Product-owner design note "Streaming AI responses & MCP best practices" (2026-09-28) — evaluated in §10 |

> **Superseded in part by [LLD-17](17-chat-ui-protocol.md):** the event contract of §3 evolves into `dai-stream/2` (surfaces, interrupts, `interaction.end`, `state.snapshot`), and §7 resumption becomes cross-node in LLD-17 §8.5. The transport rules (§2, §4–§6, §8) stand.

## 1. Purpose & responsibilities
Deliver agent turns as a live stream of typed events (text deltas, tool activity, UI-component
payloads, proposals, usage, errors) over Server-Sent Events, with correct completion, cancellation,
heartbeats, and error semantics after the `200 OK` has already been sent.
Rendering (Markdown, components) is the client's job; safety filtering stays on the server.

## 2. Endpoint
```
POST {base}/api/agents/{slug}/chat/stream
Content-Type: application/json            Accept: text/event-stream
Authorization: Bearer … | session cookie + CSRF header
{ "conversationId": "…?", "message": "…", "clientRequestId": "uuid" }
```
- **POST, not GET.** Prompts must not travel in URLs, where they end up in access logs, proxy logs,
  and browser history (PII), and hit URL-length limits. Consequence: the browser `EventSource` API
  (GET-only, cannot set `Authorization`) is not used. The client uses `fetch()` + `ReadableStream` SSE
  parsing (shipped in our JS client, §8).
- Controller returns `Flux<ServerSentEvent<StreamEvent>>`. Spring MVC adapts reactive return types on
  servlet hosts (async request processing); `reactor-core` is already a Spring AI dependency, so no WebFlux
  host is needed (ADR-0015).
- Response headers: `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no` (nginx),
  no response compression on this path (compression buffers chunks).

## 3. Event contract (versioned: `protocol: "dai-stream/1"`)
Every event has an SSE `id: <turnId>:<seq>` (monotonic) and a JSON `data` object with `type`.
| `event:` | `data` fields | When |
|----------|---------------|------|
| `turn.start` | `turnId, conversationId, agent, revision, protocol`, `ui?` = `{steps, feedback, copy, choices}` | First event; `ui` tells the client which chat features to offer (agent `output.ui`, else `dynamic.ai.agent.chat.ui.*`); absent = plain text client |
| `step` | `stepId, title, status` (`running\|done\|error`), `detail?` | Progress of a non-tool step ("Checked your request"), only when `ui.steps`; same `stepId` may be sent again with a new status |
| `text.delta` | `seq, text` | Model tokens (after server-side guardrail window, §5) |
| `tool.call` | `callId, tool, argsPreview` (PII-redacted, ≤ 300 chars) | Model requested a tool; emitted live from the tool thread when `ui.steps` (implemented, `StepReportingToolCallback`) |
| `tool.result` | `callId, status` (`ok\|empty\|truncated\|error\|not_permitted\|unavailable\|proposed`), `summary` | Tool finished; the summary is built from the envelope's status, entity, count and displayable error only, never rows |
| `ui.component` | `componentType, payload` (JSON string), `componentId?`, `copyable?` | Display data — always a complete JSON object, never split. `componentId` identifies interactive components within the turn; `copyable: true` asks the client for a copy button |
| `ui.component` (`componentType: "choice"`) | `payload` = `{componentId, question, options:[{value,label,description?}], multiple, allowOther}` | Shown when the model calls the built-in `present_choices` tool (only when `ui.choices`); texts PII-redacted; recorded so the answer can be validated (`ChatUiState`). The user answers through the next chat request (`answer` field) |
| `ui.component` (`componentType: "structured-response"`) | `payload` = the answer's display tree (LLD-06 §8.3) | Sent once, after the last `text.delta` and before `usage`/`turn.end`, when the structured display is on; PII already removed |
| `proposal.created` / `proposal.updated` / `proposal.applied` | proposal review payload / state (LLD-11) | Write flow |
| `usage` | `inputTokens, outputTokens, costMicros, model` | Before `turn.end` |
| `turn.end` | `finishReason` (`stop\|length\|tool_limit\|budget\|cancelled`), `messageId` | Last event of a successful turn |
| `error` | RFC 9457 subset: `type, title, code, retryable, turnId` | Terminal failure after 200 was sent; stream then closes |
Heartbeats are SSE **comments** (`: keep-alive`), not events — every SSE parser ignores them, so they
never reach application code.

Errors before the stream starts (authz, validation, rate limit, budget) are ordinary HTTP 4xx/5xx
problem responses — the stream is only opened after all pre-checks pass.

## 4. Stream assembly (design sketch, not implementation)
```
content  = turnFlux(ctx)                               // Spring AI .stream().chatResponse() + tool/proposal events, mapped to StreamEvent
             .transform(guardrailWindow(ctx))           // §5
             .timeout(firstTokenTimeout, idleTimeout)   // no event for N s → error(code=model_timeout)
             .take(maxTurnDuration)                     // hard cap → turn.end(finishReason=length)
heartbeat = Flux.interval(15s).map(comment)
                .takeUntilOther(content.ignoreElements())   // STOPS when content completes or errors
stream   = Flux.concat(just(turnStart), content.mergeWith(heartbeat), just(usage, turnEnd))
             .onErrorResume(e -> just(toProblemEvent(e)))   // mapped code only, never e.getMessage()
             .doOnCancel(ctx::cancel)                       // client disconnect → cancel model call + tool threads
             .doFinally(sig -> audit + metrics)
```
Rules:
- The heartbeat **must be bounded by the content stream** (`takeUntilOther`). A plain
  `mergeWith(Flux.interval(…))` never completes, so the HTTP response would stay open forever after the answer.
- Error events carry a stable `code` from our problem catalog; exception messages are logged server-side
  with the `turnId` and never sent (they can contain SQL, hostnames, or prompt text).
- Cancellation propagates: client disconnect → Reactor cancel → Spring AI stream disposed → our tool
  executor interrupts in-flight virtual threads → partial assistant message saved as `cancelled`.
- Tool execution never happens on the Reactor/servlet thread; blocking tools run on our bounded
  virtual-thread executor (LLD-12 §4) and are bridged back into the stream.
- Backpressure: the servlet output stream is the consumer; per-stream buffer capped
  (`stream.max-buffered-events`, default 256). Slow clients over the cap → `error(code=client_too_slow)` and close.

## 5. Server-side guardrail window (the one exception to "stateless server")
The server does not buffer for Markdown correctness — that is the client's job (§6). It does keep a
**small sliding hold-back window** (default 64 characters) on `text.delta`, because safety filters must see
patterns that span chunk boundaries:
- PII/secret redaction (e.g. a card number split over two chunks),
- exfiltration patterns (Markdown image/link with data in the query string),
- blocked-term policy.
Text is released once it can no longer be part of a match. At stream end the window is flushed through
the same filters. Latency cost ≈ one small chunk; configurable per agent (`guardrails.stream-window`).

**Implemented (F-76):** `StreamingPiiRedactor` keeps **128** characters back (longer than any built-in value
format), releases text only up to a whitespace boundary that is not inside a detected value, and releases a single
unbroken run longer than 8,192 characters at the window limit to bound memory. Not configurable per agent yet;
exfiltration and blocked-term filtering of the output are not implemented (OQ-55). `JSON_SCHEMA` answers are
held back whole (OQ-51) and redacted inside the document.

## 6. Client rendering responsibilities (JS client & Web Components)
- Incremental Markdown rendering with a **streaming-tolerant parser** (re-parses the accumulated text on each
  delta and hides incomplete constructs), in `<saimcp-chat-stream>`.
- **Sanitised rendering:** raw HTML disabled; images not auto-loaded (placeholder + click-to-load against an
  allow-list, as a second layer against exfiltration); links get `rel="noopener noreferrer"` and show the
  full URL before navigating; no `javascript:`/`data:` URLs.
- `ui.component` and `proposal.*` events render the matching Web Component (LLD-11 §7).
- Host apps using React may render events with their own components (e.g. `react-markdown` with
  sanitising plugins); the event contract (§3) is the integration point, not our components.

## 7. Reconnection & resumption (v1.x)
- Events for an in-flight turn are kept in a bounded in-memory ring per turn (default 500 events, 5 min).
- Client reconnects with `Last-Event-ID: <turnId>:<seq>` to `GET {base}/api/agents/{slug}/turns/{turnId}/events`
  and receives the missed events, then the live tail. Works on the same node only; on another node the client
  receives `error(code=turn_not_resumable)` and reloads the finished message from conversation history.
- v1: no resumption; a dropped connection cancels the turn (client shows "interrupted — retry").

## 8. Proxy & infrastructure notes (integration guide content)
Idle timeouts on load balancers/API gateways must exceed the heartbeat interval (15 s default);
disable response buffering for `/dynamic-ai/api/**/stream`; HTTP/2 recommended (avoids the browser's
6-connections-per-host limit under HTTP/1.1).

## 9. Observability
Timers: time-to-first-token, turn duration; counters: `finishReason`, cancellations, `client_too_slow`;
gauge: open streams per node. Span `dai.agent.stream` linked to `dai.agent.turn`.

## 10. Evaluation of the input design note
| Proposal | Verdict | Reason / change |
|----------|---------|-----------------|
| SSE over WebSockets, `Flux<ServerSentEvent>` | **Adopted** | Works on servlet hosts via MVC reactive return-type support (ADR-0015) |
| `GET /stream?prompt=…` | **Changed to POST** | Prompts in URLs leak into logs/history; `EventSource` can't send bearer tokens anyway |
| `getOutput().getContent()` | **Corrected** | Spring AI 1.0+ API is `getOutput().getText()` |
| `mergeWith(Flux.interval(15s))` heartbeat | **Corrected (bug)** | Never completes → response never closes. Bounded with `takeUntilOther`; heartbeat sent as SSE comment |
| `onErrorResume` sending `ex.getMessage()` | **Corrected (security)** | Leaks internals; send problem code only |
| Structured event types (`error`, `metadata`, `content`) | **Adopted, extended** | Full contract §3 incl. tool, component, proposal, usage events |
| Server stateless; client buffers Markdown | **Adopted with one exception** | Client renders Markdown; server keeps a small guardrail window for redaction/exfil filters (§5) |
| `react-markdown` on the client | **Adapted** | Our components are framework-neutral Web Components; React hosts can still use react-markdown — with HTML disabled and sanitising plugins |

## 11. Test strategy
StepVerifier tests: stream completes after `turn.end` (heartbeat does not keep it open); error after
first token yields one `error` event and closes; client cancel disposes the model call and tool threads;
redaction across chunk boundaries; slow-consumer cap; MockMvc async SSE parsing test; proxy test behind
nginx with buffering on/off.
