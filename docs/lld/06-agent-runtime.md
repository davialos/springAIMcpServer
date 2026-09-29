# LLD-06: Agent Runtime (Spring AI 2.x)

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | agent-runtime-designer |
| Module(s) | `core` (AgentDefinition, ports), `ai` (Spring AI adapter) |
| Related features | F-40 … F-50, F-70, F-72, F-76, F-77 |
| Related ADRs | ADR-0007, ADR-0008, ADR-0009 |

## 1. Purpose & responsibilities
Execute chat turns for published agents: assemble a `ChatClient` per agent revision,
attach tools the caller is entitled to, enforce budgets and guardrails, persist memory,
stream responses, and emit traces/usage. Tool wrapping details → LLD-07.

## 2. Data model
```java
// design sketch
public record AgentDefinition(ResourceId id, int revision, WorkspaceId workspace, String slug,
        String displayName, String systemPrompt,           // templated; may reference {catalogSummary}
        ModelSelection model,                               // providerId, modelName, temperature, maxTokens, fallback
        List<ToolBindingRef> tools,                         // → LLD-07
        MemorySpec memory,                                  // NONE | WINDOW(n) | SUMMARY; retention
        List<RagSourceRef> rag,                             // v1.x
        GuardrailSpec guardrails,                           // input/output checks, PII redaction, topic allow-list
        LimitSpec limits,                                   // maxToolCallsPerTurn, maxTokensPerTurn, turnTimeout, maxTurnsPerConversation
        OutputSpec output,                                  // TEXT | JSON_SCHEMA(schema)
        Set<CatalogElementRef> references, String catalogHash) {}
```

## 3. ChatClient assembly (cached per (agentId, revision); tools resolved per request)
```
ChatClient.builder(modelRouter.chatModel(model))
  .defaultSystem(renderedSystemPrompt)                // static part only
  .defaultAdvisors(
      InvocationGuardAdvisor        order HIGHEST+100  // budget pre-check, kill switch, input guardrails, PII redaction of user msg
      MessageChatMemoryAdvisor      order HIGHEST+200  // outside tool loop (final messages only)
      RetrievalAugmentationAdvisor  order HIGHEST+250  // v1.x, ACL-filtered retrieval
      ToolCallingAdvisor            order HIGHEST+300  // Spring AI loop; our ToolCallingManager w/ limits
      StructuredOutputValidationAdvisor (if JSON output)
      OutputGuardAdvisor            order LOWEST-100   // output guardrails, redaction, citation check
      UsageMeteringAdvisor          order LOWEST        // tokens/cost from ChatResponse metadata
  )
per request:
  .prompt().user(input)
  .tools(toolBridge.callbacksFor(agentRevision, principal))    // filtered by grants
  .toolContext(Map.of(InvocationContext.KEY, ctx))             // principal, workspace, trace; never sent to model
  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey(principal, agent, session)))
  .stream() | .call()
```
Large tool sets (> `tool-search-threshold`, default 20): swap `ToolCallingAdvisor` for
`ToolSearchToolCallingAdvisor` (progressive disclosure, fewer tokens).

Performance (LLD-14): the ToolCallingAdvisor uses our `ToolCallingManager` that runs independent read calls in parallel
(ADR-0017); prompts are laid out for provider prompt caching (system → tools in stable sorted order → conversation);
every model call passes the provider rate governor (LLD-14 §4).

## 4. Chat API
| Endpoint | Purpose |
|----------|---------|
| `POST {base}/api/agents/{slug}/chat` | Sync turn → `{conversationId, message, toolCalls[], usage}` |
| `POST {base}/api/agents/{slug}/chat/stream` | SSE stream — event contract, completion, heartbeat, cancellation and errors in **LLD-13** |
| `{base}/api/proposals/**` | Review/edit/confirm/reject change proposals (LLD-11 §8) |
| `GET/DELETE {base}/api/agents/{slug}/conversations/{id}` | History (own only) / erase (right to be forgotten) |

## 5. Reviewed writes & UI components (F-45, F-51, F-52 → LLD-11)
Mutating tools never execute inside the model loop. The tool bridge maps them to a
`ProposalTool` that builds a `ChangeProposal` (LLD-11) and returns to the model a tool result
`{status: "PROPOSED", proposalId, summary}`; the stream emits `proposal.created` + a
`ui.component` review payload. The turn ends; the user confirms/edits/rejects through the
Review API (an authenticated HTTP request, not a tool call). On `APPLIED/REJECTED/CONFLICT`
the framework appends the outcome as a tool-result message so the next turn continues with
facts. Display data (tables, cards, charts) flows to the UI via the `render_component`
tool, validated & provenance-checked (LLD-11 §7.1).

## 6. Model routing (F-49, F-77)
`ModelRouter` port: resolves `providerId` → `ChatModel` bean (host-provided Spring AI
model beans, or ones configured under `dynamic.ai.agent.models.*`). Enforces
provider allow-list per workspace and data classification (RESTRICTED ⇒ only providers
flagged `onPrem=true`). Fallback: on 5xx/timeout/circuit-open try `fallback` once.
Circuit breaker + timeout per provider (Resilience4j if present; else built-in simple breaker).

### 6.1 Provider resilience profile
A single "10 s read timeout" doesn't work for LLMs: a healthy streamed answer can run 60 s, while a
dead connection must be detected in seconds. So timeouts are split by phase:
| Timeout | Default | Applies to |
|---------|---------|-----------|
| Connect | 5 s | TCP/TLS to the provider |
| Time to first token / first byte | 20 s | Both sync and streaming calls |
| Idle between chunks (streaming) | 15 s | No token for N s ⇒ abort |
| Total turn deadline | 60 s (per agent `limits.turnTimeout`) | Whole turn including tool calls |
| Sync (non-streaming) call | 45 s | Hard read timeout |
Circuit breaker per (provider, model): **failure-rate** and **slow-call-rate** over a sliding window
(e.g. ≥ 50 % failures or ≥ 80 % calls slower than 20 s over the last 20 calls, minimum 10 calls), not
"5 in a row" (too trigger-happy at high volume, too slow at low volume). Counted as failures: 5xx,
timeouts, connection errors. **429 is not a breaker failure** — it goes to the rate governor (LLD-14 §4),
otherwise quota bursts would open the breaker for everyone. Half-open probes: 3 calls.
When open: try the configured `fallback` model once; else fail fast with problem `model_unavailable`
(`retryable: true`, `Retry-After`) whose `title` is localized through the host's `MessageSource`
(key `dynamic.ai.agent.problem.model_unavailable`, default "The AI assistant is temporarily unavailable.
Please try again shortly."). The problem is rendered by our scoped exception handler — it never reaches
the host's global handler (LLD-12 §4).

## 7. Memory (F-44)
Spring AI `ChatMemory` with our `ChatMemoryRepository` over `dai_conversation_message`
(or host-provided repository bean). Conversation key = hash(principalId, agentId, clientSessionId)
→ a user can never read another's conversation even with a guessed ID. Retention TTL
purge job; erase-on-request API. Tool results stored redacted.

**Implemented slice (history, not model memory):** `ConversationRecorder` (no-op by default) receives the user message and answer of every successful turn; `StoreConversationRecorder` stores them redacted (`MessageRedactor`) when `dynamic.ai.agent.conversations.enabled=true` (default off), keyed by the hash of (principal, agent, conversation id) with the stored conversation taking the client's id, on virtual threads behind a bulkhead. `ConversationRetentionJob` deletes expired conversations. The model's `ChatMemory` is still `InMemoryChatMemory`, unchanged (OQ-45).

## 8. Guardrails (F-76)
- Input: max length, blocked patterns, PII detectors (regex + pluggable `PiiDetector` SPI)
  → redact before sending to provider if workspace policy says so.
- Prompt-injection mitigations: system prompt states tool outputs are data; tool outputs
  wrapped in delimited blocks; no tool can change agent config; mutating tools only create reviewed proposals (LLD-11);
  output checked for exfil patterns (URLs with query data, markdown images) → stripped.
- Output: PII redaction, JSON schema validation, max length.

## 9. Limits & budgets
Per turn: `maxToolCallsPerTurn` (default 10; Spring AI manager also bounded), `maxTokensPerTurn`,
`turnTimeout` (default 60 s). Per conversation: max turns. Budget pre-check estimates
input tokens; post-turn metering debits actual usage (LLD-10). Exceed ⇒ 429 `budget-exceeded`.

## 10. Failure modes
| Failure | Behavior |
|---------|----------|
| Provider timeout / 5xx | Fallback model once → else 503 `model-unavailable` |
| Tool throws | Runtime exception message (sanitized) returned to model via `ToolExecutionExceptionProcessor`; security exceptions → "not permitted" (no details) |
| Loop runaway | Tool call cap hit → final answer forced with "limit reached" note |
| Client disconnects (SSE) | Cancel in-flight model request & tool virtual threads |
| Memory store down | Continue stateless with warning event (configurable: fail) |

## 11. Observability
Spring AI observations (gen_ai.* semantic conventions) + our span `dai.agent.turn`
(agent, revision, conversation hash, tool count, outcome). Prompt/completion **content
not** recorded by default (`dynamic.ai.agent.observability.record-content=false`).
Trace viewer (F-72) stores redacted turn transcripts in `dai_agent_trace` with retention.

## 12. Evaluation (F-48, v1.x)
`EvalSuite` per agent: cases (input, expected facts / expected tool trajectory / JSON schema),
LLM-as-judge optional with a fixed judge model. Runs on submit-for-review; results attached to the revision; policy may block publish.

## 13. Test strategy
Fake `ChatModel` scripted with tool-call responses; proposal state machine tests; injection
corpus (indirect via tool outputs); budget race tests; SSE cancellation tests.
