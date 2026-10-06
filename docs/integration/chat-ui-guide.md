# Chat UI integration guide (`<saimcp-chat>`, F-53)

How to put the library's chat window into an existing web application. The window shows:

- streamed answers in Markdown: lists, tables, code with highlighting (CEL included) and Mermaid diagrams;
- the steps behind each answer;
- like and dislike buttons;
- copy buttons;
- questions with options that stay answered after a reload;
- any component the backend sends.

| Part | Where |
|------|-------|
| Web Component | module `spring-ai-mcp-server-common-chat-ui`, served at `/dynamic-ai/ui/chat/saimcp-chat.js` |
| Stream protocol | `POST /dynamic-ai/api/agents/{slug}/chat/stream`, `dai-stream/1` (LLD-13 §3) |
| Supporting APIs | `ChatUiController`: config, ui-state, choice answers, feedback (§6 below) |
| State | `dai_chat_interaction`, `dai_turn_feedback` (V13, LLD-15), behind the `ChatUiState` port |

Related: [host-integration-guide.md](host-integration-guide.md) (starter setup, security),
[guardrails-integration-guide.md](guardrails-integration-guide.md) (prompt validation, PII removal, display tree).

---

## 1. Five-minute setup

**1. Add the jar** (version from the BOM):

```xml
<dependency>
    <groupId>com.springaimcpservercommon</groupId>
    <artifactId>spring-ai-mcp-server-common-chat-ui</artifactId>
</dependency>
```

The jar holds only static files in `META-INF/resources`. Spring Boot's default static resource handling serves them,
so no bean or controller is involved.

**2. Put the element on a page** of your application:

```html
<script type="module" src="/dynamic-ai/ui/chat/saimcp-chat.js"></script>
<saimcp-chat agent="order-helper" width="420" height="640"
             greeting="Ask about orders, invoices or customers."></saimcp-chat>
```

A launcher button appears bottom right. It opens a 420 × 640 window that the user can resize from its top, left and
right edges and corners, or maximize. The size the user picks is remembered per agent in `localStorage`.

**3. Give it the user's credentials.** The library's data plane accepts bearer tokens, validated by your own
`JwtDecoder` or `OpaqueTokenIntrospector` (host-integration guide §5). Hand the component a token:

```js
document.querySelector('saimcp-chat').tokenProvider = async () => auth.getAccessToken();
```

Session-cookie-only applications must also let the data plane accept the session. Declare your own
`DynamicAiSecurityOptions` bean with `dataPlaneSessions = true`; requests then need your CSRF header (see `headers`
below).

**4. Optional: let the agent ask questions with options** (the `present_choices` tool is off by default):

```yaml
dynamic:
  ai:
    agent:
      chat:
        ui:
          choices: true
```

---

## 2. Element reference

### Attributes

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `agent` | (required) | Agent slug |
| `base-url` | `''` (same origin) | Backend origin/prefix, e.g. `https://api.example.com` (needs CORS on the host) |
| `mode` | `floating` | `floating`: launcher button and window. `inline`: fills its container, with no launcher or resize. |
| `open` | absent | Present means the window is open. It is set and cleared as the user opens and closes it. |
| `width`, `height` | `420`, `640` | Initial window size in px. The user's resized size wins once saved. It is clamped to the viewport, with a minimum of 300 × 360. |
| `position` | `bottom-right` | Or `bottom-left` |
| `theme` | `auto` | `light`, `dark`, or `auto` (follows `prefers-color-scheme`) |
| `title` | the agent's display name | Header title |
| `placeholder` | `Ask a question…` | Composer placeholder |
| `greeting` | `Ask <agent> a question to get started.` | Text of an empty conversation |
| `credentials` | `same-origin` | `fetch` credentials: `same-origin`, `include` (cross-origin cookies) or `omit` |
| `persist` | `session` | Where the open conversation id is kept: `session` (this tab), `local` (across tabs and restarts) or `none` |
| `mermaid-src` | jsDelivr `mermaid@11` ESM | URL of the Mermaid ESM build (see §8) |
| `image-hosts` | none | Comma-separated hosts whose Markdown images load without a click |

### Properties

| Property | Meaning |
|----------|---------|
| `tokenProvider` | `async () => token`. A bare token is sent as `Authorization: Bearer <token>`; a value with a scheme (`Bearer …`, `ApiKey …`) is sent as is. It is called before every request, so return a fresh token. |
| `headers` | An object, or `async () => object`, of extra headers. Example: your CSRF header when the data plane accepts session cookies (`dataPlaneSessions`, step 3). |

### Methods

`open()`, `close()`, `toggle()`, `send(text)` (send as if typed) and `newConversation()`.

### Events

All events bubble and are composed. Each one carries `detail`.

| Event | `detail` |
|-------|----------|
| `saimcp-open`, `saimcp-close` | `{}` |
| `saimcp-turn-start` | `{message}` |
| `saimcp-turn-end` | `{turnId, status, text}` |
| `saimcp-error` | `{title, code, retryable, …}` (problem fields) |
| `saimcp-answer` | `{turnId, componentId, answer}` when the user answers a choice |
| `saimcp-feedback` | `{turnId, rating, reason}` (`rating`: `up`, `down` or `null` when withdrawn) |
| `saimcp-conversation` | `{conversationId}` when a conversation starts or is cleared |

Example: send likes and dislikes to your analytics.

```js
chat.addEventListener('saimcp-feedback', (e) => analytics.track('assistant_feedback', e.detail));
```

### Embedding notes

- **Isolation.** Everything renders in a Shadow DOM, so host CSS does not leak in and the chat's CSS does not leak
  out. The window is `position: fixed` with `z-index: var(--saimcp-z-index)`; lower the z-index if your modals must
  cover it.
- **Frameworks.** The component works in React, Angular, Vue and server-rendered pages. Set the properties after
  mount (React: through a `ref`) and listen with `addEventListener`.
- **Several instances.** One element per agent is fine. Each keeps its own size and conversation (the storage key
  includes `base-url` and `agent`).
- **Accessibility.**
  - The window is a labelled `dialog` region and the message list is an `aria-live` log.
  - Choices use radio and checkbox roles.
  - All actions are buttons with labels, and keyboard use works throughout: Enter sends, Shift+Enter adds a new
    line, Escape closes the floating window. While an answer streams, the send button becomes Stop.

---

## 3. What the user sees, and what drives it

Every feature is chosen by the backend, per agent, and announced in `turn.start.ui` (and in `GET …/chat/config` before
the first turn):

```json
{ "type": "turn.start", "turnId": "…", "conversationId": "…", "agent": "order-helper", "revision": 3,
  "protocol": "dai-stream/1", "ui": { "steps": true, "feedback": true, "copy": true, "choices": false } }
```

| Feature | Flag | Stream events | UI |
|---------|------|---------------|----|
| Markdown answer | always | `text.delta` | rendered as it streams; unfinished constructs (an open fence, a half table) are shown as plain text until complete |
| Step details | `steps` | `step`, `tool.call`, `tool.result` | collapsible "Used 2 tools" block above the answer, like Claude's; each step with a status icon and a redacted argument preview / result summary (never rows) |
| Like / dislike | `feedback` | — | thumbs under each finished answer; a dislike opens reason chips (`inaccurate`, `incomplete`, `off_topic`, `unsafe`, `other`) and an optional comment; stored with `PUT …/feedback` |
| Copy | `copy` | `ui.component.copyable` | copy button on the answer (Markdown source), on every code block and diagram, and on each component the backend marks `copyable: true` |
| Choices | `choices` | `ui.component` `choice` | option buttons; single choice without "other" answers on click, otherwise a Send button; answered once, then locked |
| Structured display | `dynamic.ai.agent.guardrails.structured-display` (guardrails guide §7) | `ui.component` `structured-response` | fields and tables from the backend display tree, PII already removed |
| Write proposals | write flow (LLD-11) | `proposal.*` | card with a link to review the change |

Copy buttons are a backend decision too. The flag in the stream (`ui.copy` for the whole turn, `copyable` per
component) decides whether they appear, so an agent can turn them off where copying is not wanted.

### Agent-level settings

The host defaults come from `dynamic.ai.agent.chat.ui.*`:

| Property | Default | Meaning |
|----------|---------|---------|
| `enabled` | `true` | Send `turn.start.ui` and the step/component events at all; `false` keeps the plain text stream |
| `steps` | `true` | Stream step details |
| `feedback` | `true` | Offer like/dislike |
| `copy` | `true` | Offer copy buttons |
| `choices` | `false` | Give the model the `present_choices` tool |
| `interaction-retention` | `30d` | How long shown components and answers are kept |
| `feedback-retention` | `365d` | How long feedback is kept |

An agent overrides any flag in its spec. A flag the spec leaves out keeps the host default:

```json
{ "output": { "ui": { "steps": true, "feedback": true, "copy": false, "choices": true } } }
```

---

## 4. Markdown support

`markdown.js` is a small renderer that escapes first and builds HTML only from recognised syntax. Model output never
reaches `innerHTML` as HTML.

| Construct | Notes |
|-----------|-------|
| Headings `#`…`######`, paragraphs, line breaks | |
| `**bold**`, `*italic*`, `~~strike~~`, `` `code` `` | |
| Lists | ordered and unordered, nested by indentation, loose and tight, task items `- [x]` |
| Tables (GFM) | header row, `:---` / `:---:` / `---:` alignment, inline formatting in cells, horizontal scroll when wide |
| Code fences | ```` ```lang ```` with a language label, a copy button and highlighting for CEL, Java, Kotlin, C#, Go, C/C++, JavaScript/TypeScript, Python, SQL, JSON, YAML, Bash, XML/HTML and CSS; other languages show plain text |
| Mermaid | ```` ```mermaid ```` blocks become diagrams with **Source** and **Copy** buttons; while streaming, or when Mermaid is unavailable or the diagram does not parse, the source is shown |
| Block quotes, horizontal rules | |
| Links | only `http:`, `https:`, `mailto:` and relative URLs; bare URLs are linked; `target=_blank`, `rel="noopener noreferrer nofollow"` |
| Images | a placeholder with the alt text and host, loaded on click (exfiltration guard, LLD-13 §6), or directly when the host is in `image-hosts` |
| Raw HTML | shown as text, never interpreted |

---

## 5. Choices that stay answered

1. When `choices` is on, the model gets the built-in `present_choices` tool.
   - Arguments: `question`, `options` (2–12, each `value`, `label` and optional `description`), `multiple` and
     `allowOther`.
   - The tool validates the arguments and redacts PII from the question and labels.
   - It records the component in `dai_chat_interaction` and streams it as a `ui.component` of type `choice`.
2. The user clicks.
3. The component calls `POST …/turns/{turnId}/components/{componentId}/answer` with `{values, other?}`.
   - The server validates the answer against the options it actually showed, never trusting the client's copy.
   - It stores the answer once (a second answer gets `409`).
   - It returns the `message` the client sends as the next chat turn, e.g. `My answer to "Which order do you mean?":
     PO-1002`. The model therefore sees the answer in the conversation, on any replica.
4. On reload, the component reads `GET …/ui-state` and shows the choice answered and locked, with the selected
   option.

Without a persistent store (a host-supplied `ChatUiState` with `persistent() == false`), the answer endpoint answers
`409 Answers are not kept`. The component then sends the answer as a plain message, and the choice is not restored
after a reload.

---

## 6. Backend API surface

All endpoints sit under `/dynamic-ai/api/agents/{slug}`. Each needs `agent:invoke` on the agent, exactly like the chat.
State is addressed by the hash of (workspace, agent, caller, conversation), so a caller only ever sees and changes
their own conversations.

| Method and path | Body | Answer |
|-----------------|------|--------|
| `POST /chat/stream` | `{conversationId?, message, clientRequestId?}` | SSE `dai-stream/1` (LLD-13) |
| `POST /chat` | same | JSON `{conversationId, turnId, message, toolCalls:[…], usage, display?, components?}` (non-streaming clients) |
| `GET /chat/config` | — | `{agent, displayName, protocol, ui:{…}, maxMessageChars, persistentState}` |
| `GET /conversations/{id}/ui-state` | — | `{persistent, components:[{turnId, componentId, componentType, payload, answer, shownAt, answeredAt}], feedback:[{turnId, rating, reason, updatedAt}]}` |
| `POST /conversations/{id}/turns/{turnId}/components/{componentId}/answer` | `{values:[…], other?}` | `200 {turnId, componentId, answer:{values, labels, other?}, message}`; `400` invalid; `404` unknown; `409` already answered or not kept |
| `PUT /conversations/{id}/turns/{turnId}/feedback` | `{rating:"up"\|"down", reason?, comment?}` | `204`; `400` invalid (comment ≤ 2000 chars, stored PII-redacted) |
| `DELETE /conversations/{id}/turns/{turnId}/feedback` | — | `204` |
| `GET /dynamic-ai/api/conversations/{id}/messages` | — | transcript (when `dynamic.ai.agent.conversations.enabled`), used to restore a reloaded chat |

Errors are `application/problem+json` (RFC 9457).

---

## 7. Security

- **Assets.**
  - The library's API chain permits anonymous `GET /dynamic-ai/ui/**`. These are static files with no data, and the
    `<script>` tag loads before a token exists. Everything the component then calls is authenticated.
  - If you moved the library's base path, or turned off static resources (`spring.web.resources.add-mappings=false`),
    serve and permit `/dynamic-ai/ui/chat/*` yourself, or copy the files into your own static assets.
- **Content-Security-Policy of your page.** The component needs:
  - `script-src 'self'` for the module, plus the Mermaid origin if you keep the CDN default;
  - `connect-src` for the backend;
  - `style-src 'unsafe-inline'`, because the Shadow DOM `<style>`, table alignment and Mermaid's SVG styles are
    inline. Hosts that forbid inline styles lose table alignment and diagram styling, not function.
- **Rendering.**
  - Markdown is escaped first. Links are limited to safe schemes, and images need a click unless allow-listed.
  - Mermaid runs with `securityLevel: "strict"`, so there are no scripts or click handlers in diagrams.
  - Components render through the registry, never as model-supplied HTML.
- **PII.**
  - Questions, option labels and typed answers are redacted on the server before they are stored or streamed.
  - Feedback comments are redacted too. The answer text was already through the guardrails (guardrails guide).
- **Storage in the browser.** Only the conversation id (`sessionStorage` by default) and the window size
  (`localStorage`) are kept. No message content is kept.

---

## 8. Mermaid hosting

Mermaid is about 3 MB and loads only when the first diagram appears. The default is
`https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs`. For intranets or a strict CSP, self-host it:

```html
<saimcp-chat agent="order-helper" mermaid-src="/assets/mermaid/mermaid.esm.min.mjs"></saimcp-chat>
```

Copy the whole `dist/` folder of the `mermaid` npm package, because the ESM build loads its chunks relatively. If the
page already has `window.mermaid`, that instance is used.

---

## 9. Theming

Set CSS custom properties on the element:

```css
saimcp-chat {
  --saimcp-accent: #0b5cad;          /* buttons, links, selection */
  --saimcp-accent-fg: #fff;
  --saimcp-bg: #fff;  --saimcp-surface: #f5f6f8;  --saimcp-fg: #1d2330;  --saimcp-muted: #5f6878;
  --saimcp-border: #dfe3ea;  --saimcp-user-bg: #e8effc;  --saimcp-code-bg: #f3f4f7;
  --saimcp-danger: #c4302b;  --saimcp-success: #1f7a4d;
  --saimcp-radius: 12px;  --saimcp-shadow: 0 12px 40px rgba(20,30,50,.22);
  --saimcp-font: "Inter", sans-serif;  --saimcp-mono: "JetBrains Mono", monospace;
  --saimcp-z-index: 2000;
}
```

`theme="dark"` (or `auto` with a dark OS setting) switches to the built-in dark palette. Your properties still win.

---

## 10. Rendering your own components

The backend can stream any component, as a `ui.component` with your `componentType` and a JSON `payload`. Register a
renderer for each type, before or after the element is defined:

```js
import { SaimcpChat } from '/dynamic-ai/ui/chat/saimcp-chat.js';

SaimcpChat.registerComponent('order-card', (component, ctx) => {
  const el = document.createElement('div');
  el.textContent = `${component.payload.number}: ${component.payload.status}`;
  const btn = document.createElement('button');
  btn.textContent = 'Track it';
  btn.onclick = () => ctx.sendMessage(`Track order ${component.payload.number}`);
  el.append(btn);
  return el;
});
```

Alternatively, define a custom element whose tag equals the `componentType`, for example `order-card`. It receives
`.payload` and `.chatContext`.

| `component` | `ctx` |
|-------------|-------|
| `type`, `componentId`, `payload` (parsed), `answer` (restored, if any), `copyable` | `turnId`, `conversationId`, `interactive`, `copyEnabled`, `persistent`, `copy(text)`, `sendMessage(text)`, `emit(name, detail)`, `answer(component, body)` |

- A renderer that throws falls back to a collapsible JSON view, so a bad payload never breaks the conversation.
- Unknown types render as JSON too.
- Treat `payload` as untrusted data. Build DOM with `textContent` and not `innerHTML`.

---

## 11. Scaling and operations

- **Stateless by default (ADR-0021).**
  - Answers and feedback are in PostgreSQL, and the stream runs per request, so any replica serves any call behind a
    round-robin balancer.
  - A host at scale can supply its own `ChatUiState` bean (`@ConditionalOnMissingBean`).
- **Retention.** The conversation retention job deletes expired interactions and feedback in bounded batches
  (`interaction-retention`, `feedback-retention`).
- **Reporting.** `dai_turn_feedback` has `workspace_id`, `agent_id` and an index for per-agent queries. An admin view
  does not exist yet (OQ-73).
- **Proxies.** Turn off buffering and compression on `/chat/stream` (LLD-13 §8). The stream sends `: keep-alive`
  comments.

---

## 12. Trying it without a backend

```bash
node spring-ai-mcp-server-common-chat-ui/demo/mock-server.mjs   # http://localhost:8099/
scripts/chat-ui-test.sh                                          # unit tests (Node 20+)
```

The mock speaks the same protocol and APIs with scripted answers. Try:

- "show me everything": Markdown, a table, CEL and Java code, a Mermaid diagram, steps and a structured display;
- "which order is late?": a choice;
- "fail please": a retryable error.

---

## 13. Relation to LLD-17 (`dai-stream/2`)

This guide describes the implemented `dai-stream/1` chat (LLD-13 §3). LLD-17 / ADR-0023 design a successor with:

- one `/interactions` envelope;
- server-declared JSON surfaces (`dai-ui/1`);
- `ask_user` interrupts.

When that lands:

- `choice` maps onto a question surface;
- the answer endpoint maps onto an `action`/`respond` interaction;
- the component registry stays the extension point.

The open questions list tracks the overlap, including the `dai_chat_interaction` table name, which LLD-17 §12 also
sketches.
