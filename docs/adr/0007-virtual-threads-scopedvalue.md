# ADR-0007: Virtual threads for I/O paths, ScopedValue for invocation context, explicit bulkheads
- Status: Proposed · Date: 2026-09-27

## Context
LLM calls are slow (seconds) and tool calls fan out; Java 25 offers final virtual threads and Scoped Values (JEP 506).

## Decision
Use a library-owned virtual-thread executor for LLM/tool/MCP I/O; carry `InvocationContext` in a `ScopedValue`; propagate Spring `SecurityContext` explicitly; guard each downstream with semaphore bulkheads and timeouts. Avoid preview features (Structured Concurrency) in the public API.

## Consequences
+ High concurrency without reactive stack. − Must still bound downstream concurrency; ThreadLocal-based host libs need explicit propagation.
