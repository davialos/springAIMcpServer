# ADR-0014: Enforcing read-only for AI-invoked actions (write guard)
- Status: Proposed · Date: 2026-09-28

## Context
The design note proposed a Spring AOP `@Around` aspect on `@AiExposedAction`, using a `ThreadLocal` flag and
rejecting methods whose `@Transactional` isn't read-only. Problems:
(1) it proxies every annotated host bean and runs on every normal (non-AI) call — a host-wide side effect;
(2) a `ThreadLocal` doesn't follow virtual-thread/async hand-offs reliably;
(3) checking the `@Transactional` annotation is a heuristic: it misses `repository.save()` inside un-annotated
methods, `REQUIRES_NEW`, and JDBC writes, and it falsely blocks read methods under a class-level `@Transactional`.

## Decision
Enforce at the only AI entry point (our tool bridge, LLD-07) plus the persistence layer, in depth:
1. **Registration:** `readOnly=false` actions are never directly executable tools — proposal-only (ADR-0009).
2. **Invocation context:** `ScopedValue<InvocationContext>` with mode `AI_READ` (ADR-0007), bound only around AI read-tool calls.
3. **Read-only transaction:** AI read calls run inside our `TransactionTemplate(readOnly=true)` → Hibernate read-only
   session / `FlushMode.MANUAL` and `Connection.setReadOnly(true)` (PostgreSQL and MySQL then reject DML at the database).
4. **Hibernate write veto (JVM level):** a Hibernate `Integrator` registers pre-insert/update/delete and collection
   listeners that throw `AiWriteViolationException` when the current scope is `AI_READ` — this also catches
   `REQUIRES_NEW` on the same thread. It is a no-op outside AI scope (one scoped-value read per flush). On by default; opt-out.
5. **Optional JDBC guard:** opt-in `DataSource` wrapper that rejects non-SELECT statements in `AI_READ` scope (it wraps a host bean, hence opt-in).
6. **Reaction:** violation → tool result "not permitted", audit `AI_WRITE_VIOLATION`, metric; after N violations a tool is
   automatically disabled through the kill-switch layer (LLD-03 §4) until an admin re-enables it.

## Consequences
+ No proxies on host beans; no effect on non-AI traffic; catches real writes rather than guessing from annotations.
− Residual gaps: JDBC writes on another thread or a separate connection when the optional JDBC guard is off, and databases
  where `setReadOnly` is only a hint. These are documented and covered by the proposal-only rule for anything declared as a write.
