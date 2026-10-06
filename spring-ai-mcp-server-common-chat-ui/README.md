# spring-ai-mcp-server-common-chat-ui

`<saimcp-chat>`: an embeddable chat window for the library's agents (F-53). It is a Web Component made of plain
ES modules, with no build step and no runtime dependencies. It speaks the `dai-stream/1` SSE protocol and the supporting
chat APIs (LLD-13 §3).

The full integration guide is [docs/integration/chat-ui-guide.md](../docs/integration/chat-ui-guide.md).

## Use it in a host

```xml
<dependency>
    <groupId>com.springaimcpservercommon</groupId>
    <artifactId>spring-ai-mcp-server-common-chat-ui</artifactId>
</dependency>
```

Spring Boot serves the jar's `META-INF/resources` at `/dynamic-ai/ui/chat/`, so a page needs two lines:

```html
<script type="module" src="/dynamic-ai/ui/chat/saimcp-chat.js"></script>
<saimcp-chat agent="order-helper" width="420" height="640"></saimcp-chat>
```

## Layout

| Path | What |
|------|------|
| `src/main/resources/META-INF/resources/dynamic-ai/ui/chat/saimcp-chat.js` | the element: window, resize, conversation, actions |
| `…/client.js` | SSE parser and API client (`fetch`, bearer token, problem+json errors) |
| `…/turn.js` | reducer from stream events to a turn (text, steps, components, usage, error) |
| `…/markdown.js` | streaming-tolerant, escape-first Markdown renderer (GFM tables, task lists, fences, Mermaid blocks) |
| `…/highlight.js` | small syntax highlighter (CEL, Java/C-like, JS/TS, Python, SQL, JSON, YAML, Bash, XML, CSS) |
| `…/mermaid.js` | lazy Mermaid loader (`securityLevel: strict`) |
| `…/components.js` | `ui.component` registry: `choice`, `structured-response`, `proposal.*`, custom elements, JSON fallback |
| `…/styles.js` | Shadow DOM styles and `--saimcp-*` theme tokens |
| `src/test/js/*.test.mjs` | Node unit tests |
| `demo/` | mock backend and a stand-in host page (development only, not packaged) |

## Develop

```bash
scripts/chat-ui-test.sh                                       # unit tests (Node 20+, nothing to install)
node spring-ai-mcp-server-common-chat-ui/demo/mock-server.mjs # then open http://localhost:8099/
```

The demo backend scripts its answers. Try "show me everything" (Markdown, tables, code, Mermaid and steps), "which
order is late?" (a choice) or "fail please" (an error with Retry).
