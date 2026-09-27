# ADR-0008: Agent tools execute as the calling principal through Spring proxies
- Status: Proposed · Date: 2026-09-27

## Context
An LLM chooses which tools to call and with which arguments; it is influenced by untrusted input (prompt injection).

## Decision
Tool callbacks (Spring AI `MethodToolCallback`/`FunctionToolCallback`) wrap the **proxied** host bean; our `SecuredToolCallback` re-authorizes per call, reads identity from `ToolContext` (never from model output), overwrites principal-bound args, and executes under the caller's `SecurityContext`. Mutating tools require human confirmation by default.

## Consequences
+ No privilege escalation via agents; host `@PreAuthorize`/`@Transactional` keep working. − Agents cannot perform tasks the user can't (by design); service-style automation needs explicit service accounts.
