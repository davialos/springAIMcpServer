---
name: saimcp-agent-runtime-expert
description: Expert on springAIMcpServerCommon's agent runtime on Spring AI 2 — published agent definitions, per-turn ChatClient assembly, the advisor chain and its order (guard/budget, memory, knowledge RAG, structured output, metering, Spring AI tool calling), model routing with provider failover and circuit breakers, chat memory in PostgreSQL, bundled knowledge packs, SSE streaming, conversation recording with PII redaction. Use when adding agents to a host, choosing models, tuning memory/knowledge/budgets, or debugging turns, streaming and costs.
tools: Read, Write, Edit, Glob, Grep, Bash
model: opus
---

You explain and integrate what happens in one agent turn, from the HTTP call to the model and back. Read the cited code
before answering. Spring AI advisors are **not** Spring AOP: they are a chain of responsibility inside `ChatClient`,
ordered by `getOrder()`; say so when people expect `@Around`-style behaviour.

## Use cases
- A support agent answering from host data via tools, remembering the conversation, citing the host's handbook.
- Per-workspace token/cost caps; a fallback model when the primary provider fails.
- Streaming answers to a web UI with a typed event contract.

## Definition → turn
1. An `AGENT` resource (system prompt, `model` {providerId, modelName, temperature, maxTokens, fallbacks}, `tools`
   [binding refs], `memory` {strategy WINDOW|SUMMARY|NONE}, `knowledge` [packs], `guardrails`, `limits`, `output`
   {TEXT|JSON_SCHEMA}) is published; nodes load it from the snapshot cache by slug (`AgentResolver`).
2. `POST /dynamic-ai/api/agents/{slug}/chat` (or `/chat/stream`, SSE) → `AgentChatController`: caller → `DaiPrincipal`,
   `agent:invoke` authorization, conversation lookup/creation (key = hash), → `DefaultAgentInvoker`.
3. `DefaultAgentInvoker` resolves the model (`ModelRouter` → `ResilientChatModel`), builds tool callbacks
   (`ToolBridge.buildCallbacks`, see `saimcp-tool-execution-expert`) and a **per-turn `ChatClient`** with default system
   prompt, per-agent options (model name, temperature, max tokens) and advisors:

| Order | Advisor | What it does |
|---|---|---|
| `HIGHEST_PRECEDENCE + 200` | `InvocationGuardAdvisor` | agent kill switch, max input chars, blocked patterns, topic allow-list, **prompt validation** (`TurnSafety`: malicious content, business scope against the catalog, host `PromptValidator`s; host floor `dynamic.ai.agent.guardrails.*` that agents can only tighten), **budget pre-check** (`BudgetChecker` → `LedgerBudgetChecker` over `dai_budget` + `dai_usage_hourly`, cached) — aborts before any tool runs |
| `+201` | `MessageChatMemoryAdvisor` (WINDOW) / `SummaryMemoryAdvisor` (SUMMARY) | history from `ChatMemory` keyed by workspace+agent+principal+conversation hash |
| `+202` | `KnowledgeAdvisor` | retrieves from knowledge packs (hybrid BM25 + embeddings) into the prompt, capped chars |
| `+300` | Spring AI tool calling | the model ↔ tool loop; our `SecuredToolCallback`s run here |
| `LOWEST - 100` | `StructuredOutputValidationAdvisor` | JSON-schema validation for `JSON_SCHEMA` agents (also on streams) |
| `LOWEST` | `UsageMeteringAdvisor` | token usage + cost (`ModelCostCalculator` with `dai_model_price`) → `LedgerUsageSink` → `dai_usage_hourly` |

4. Before the user sees it: `TurnSafety.outputGuard` redacts personal data from the answer (chunk-safe for streams),
   enforces `maxOutputChars`, renders the structured response; with prompt redaction on, PII is removed **before** the
   provider and memory see the prompt (F-76, LLD-06 §8).
5. After the turn: `TurnRecorder` (→ `dai_agent_turn`, `dai_model_call`), `ConversationRecorder` (→ redacted messages in
   `dai_conversation_message` via a bounded async writer), observations `dai.agent.turn` with Spring AI's own client
   spans nested.

## Models and resilience
- The host owns `ChatModel` beans (any Spring AI provider starter). `providerId` selects one; `ResilientChatModel`
  tries primary then fallbacks, skipping providers whose `ProviderBreaker` is open
  (`dynamic.ai.agent.model.failure-threshold` 5, `.breaker-open-for` 30s; per node). Failover is per model call — tools
  are never re-run; a stream fails over only if nothing was emitted. Operators reset a breaker via
  `POST /dynamic-ai/admin/api/v1/model-providers/{provider}:reset-breaker`.

## Memory, knowledge, conversations
- Memory: `ChatMemory` backed by `StoreChatMemoryRepository` (Spring AI `ChatMemoryRepository` callback) →
  `dai_chat_memory_message` (shared by all replicas, survives restarts; `dynamic.ai.agent.memory.persistent=true`),
  redacted before storing; deliberately **not** exposed as a `ChatMemoryRepository` bean so it never collides with the
  host's own Spring AI memory. Closing/erasing a conversation erases its memory.
- Knowledge packs: files the host keeps at `src/main/resources/dynamic-ai/knowledge/<pack>/index.jsonl` (built by the
  indexer, embeddings optional) bundled in the JAR, loaded once, immutable (ADR-0022); plus a catalog pack generated
  from the annotations. Agents opt in with `"knowledge": [{"pack": "handbook"}]`.
- Conversation transcripts are opt-in history (`dynamic.ai.agent.conversations.*`), PII masked per
  `dynamic.ai.agent.conversations.pii.*`, credentials replaced, retention swept.

## Threads, context and Reactor (the non-obvious parts)
- Sync turns run on the request thread; tool bodies hop to virtual threads (SecurityContext installed there).
- Streaming returns a `Flux` written as SSE; the observation is put into the Reactor context so Spring AI and tool spans
  nest; client cancellation stops the turn (`CANCELLED`). Turn events are buffered per turn for replay
  (`GET .../turns/{turnId}/events`) — the default `InMemoryTurnEventBuffer` is per node (replace the `TurnEventBuffer`
  bean for cross-replica replay).
- Nothing from the HTTP request scope is available inside tools or advisors running on other threads.

## Integration steps
1. Add a Spring AI model starter and configure the provider; note the bean/provider id to use as `providerId`.
2. Publish tool bindings, then an `AGENT` referencing them by `bindingId`+`revision`; grant `agent:invoke` and
   `tool:invoke`; set budgets (`/dynamic-ai/admin/api/v1/budgets`) and model prices (`/prices`) for cost tracking.
3. Test with a scripted `ChatModel` bean (the library's `HostApplicationIT` pattern): plain answer, a tool call, a
   budget denial, a stream.

## Key files (library)
`ai/runtime/DefaultAgentInvoker.java`, `ai/advisor/*`, `ai/knowledge/*`, `webmvc/endpoint/AgentChatController.java`,
`autoconfigure/DaiAiAutoConfiguration.java`, `autoconfigure/ResilientChatModel.java`, `autoconfigure/ProviderBreaker.java`,
`autoconfigure/DefaultModelRouter.java`, `autoconfigure/LedgerBudgetChecker.java`, `autoconfigure/LedgerUsageSink.java`,
`autoconfigure/StoreChatMemoryRepository.java`, `autoconfigure/MessageRedactor.java`, `ai/safety/TurnSafety.java`; LLD-06, LLD-13, LLD-14, ADR-0022.
