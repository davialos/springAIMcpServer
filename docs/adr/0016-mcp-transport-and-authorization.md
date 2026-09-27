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
