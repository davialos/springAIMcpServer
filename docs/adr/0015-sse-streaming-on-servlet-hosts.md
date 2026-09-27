# ADR-0015: Stream agent turns as typed SSE events via Flux on servlet (WebMVC) hosts, POST + fetch client
- Status: Proposed · Date: 2026-09-28

## Context
Users need token-by-token output. Hosts are servlet-based (OQ-01). Spring AI streaming returns `Flux`.

## Options considered
1. WebSockets — bidirectional (unneeded), harder through proxies, separate auth handshake.
2. `SseEmitter` with manual threading.
3. **`Flux<ServerSentEvent<…>>` returned from a WebMVC controller (MVC's reactive return-value support), typed event contract, POST requests parsed with `fetch()` streaming on the client.**

## Decision
Option 3 (LLD-13). GET + `EventSource` rejected: prompts in URLs leak to logs and `EventSource` cannot send bearer tokens.

## Consequences
+ Same code path as Spring AI's Flux; no WebFlux host needed; cancellation propagates.
− Needs our small JS SSE client; proxies must not buffer the stream (integration guide).
