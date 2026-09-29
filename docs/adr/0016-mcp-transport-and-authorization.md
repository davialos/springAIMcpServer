# ADR-0016: MCP server over Streamable HTTP inside the host, OAuth 2.1 protected resource, no embedded STDIO
- Status: Proposed · Date: 2026-09-28

## Context
The design note recommends STDIO for sidecars and SSE for remote access. Since MCP spec 2025-03-26 HTTP+SSE has been
replaced by Streamable HTTP, and Spring AI 2.0 deprecates the SSE server transport. Spring AI's HTTP transports are
unauthenticated by default.

## Decision
Streamable HTTP by default, Stateless as an option for scale-out, legacy SSE opt-in only, no STDIO in the embedded
library (optional stdio→HTTP bridge CLI instead). The endpoint is an OAuth 2.1 protected resource per the 2025-11-25 MCP
authorization spec (RFC 9728 metadata, audience-bound tokens, no token passthrough), with an approved-client registry
and per-call scope checks (LLD-07 §5).

## Consequences
+ Spec-aligned, reuses the host's authorization server, works across replicas (stateless mode).
− Stateful mode needs sticky sessions for notifications; STDIO-only clients need the bridge.

## Amendment 2026-09-29 — the endpoint speaks the protocol directly
- **Decision.** The stateless Streamable HTTP endpoint is implemented in the `mcp` module (`McpProtocolHandler`,
  `McpEndpointController`) instead of on top of the MCP Java SDK / Spring AI MCP server starter.
- **Why.** The SDK 2.0 server API could not be verified when the endpoint was written (documentation unreachable, and
  the project rule is never to guess an API). The stateless wire format (JSON-RPC 2.0 over one POST) is small and
  specified, and doing it directly makes the per-request guarantees explicit: the caller's Spring Security
  authentication reaches every tool, the tool list is computed per caller on each request, and every request and tool
  call is recorded.
- **Consequences.** We own protocol conformance for `initialize`, `ping`, `tools/list`, `tools/call` (tested in
  `McpProtocolHandlerTest`); resources, prompts, sampling, batches, server-initiated messages and sessions are not
  supported. `McpProtocolHandler` and the controller are separate so an SDK-based transport can replace the
  controller later without touching tool listing, security or recording. Revisit once the SDK API is verified,
  particularly for stateful mode and `tools/list_changed` (OQ-49).
