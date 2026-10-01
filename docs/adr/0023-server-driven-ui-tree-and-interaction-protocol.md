# ADR-0023: Server-driven UI tree with one interaction envelope and suspend-to-ask interrupts
- Status: Proposed · Date: 2026-10-01
- Deciders: product owner request (2026-09-30), lld-chief-architect

## Context
Chat needs the AI to show structured results, ask the user questions, and receive confirmations and UI instructions back, through one consistent API. Today only free text goes in, `ui.component`, `tool.*` and `proposal.*` events exist but are never emitted, proposals are confirmed through a separate REST API with no link from the stream, and replay is node-local (OQ-42). Writes must stay user-reviewed (ADR-0009), the library must stay stateless (ADR-0021) and default-deny.

## Options considered
1. Extend `ui.component` and add one REST endpoint per kind of user input.
2. Adopt AG-UI wholesale as the wire protocol.
3. Adopt A2UI wholesale (flat adjacency-list components).
4. MCP Apps style: sandboxed HTML from the server.
5. **Own protocol: closed-catalog nested JSON tree, server-held actions, one interaction envelope, interrupts that end the run, validated JSON Schemas; adapters to AG-UI and MCP later.**

## Decision
Option 5 (LLD-17). Key choices:
- one `POST …/interactions` with four types (`message`, `action`, `respond`, `cancel`) and one event stream (`dai-stream/2`, SSE or JSON batch);
- UI is declarative data (`dai-ui/1`): `model_safe` nodes may be model-authored, interactive nodes are `server_only`; actions are opaque ids resolved from a server-held record;
- the AI asks through `ask_user`; an interrupt ends the turn and the answer resumes it on any node; only a server-built proposal review can authorize a write;
- model-authored data binds to tool-result handles instead of copying rows (replaces the hash check of LLD-11 §7.1);
- question schemas and outcomes use the MCP elicitation subset; node dispatch in the schema is `if/then`, never `oneOf` over recursive nodes.

## Consequences
+ one client reducer for live, replay and reload; no sticky sessions; forged actions and stale confirmations are refused by construction; MCP and AG-UI mappings are mechanical.
+ every example is machine-validated (`docs/schemas/validate.mjs`).
− new persistence (V11) and a renderer to build; `dai-stream/1` events change (no client exists yet); `ask_user` needs the advisor re-registration spike (S-1); a model-raised confirmation costs the user an extra click before the authoritative proposal review.
− A2UI and AG-UI wire formats are not used directly; adapters are future work (OQ-62).
