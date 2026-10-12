# ADR-0031: Annotation-driven mock data with AI fallback and a virtual-thread load simulator
- Status: Accepted
- Date: 2026-10-12
- Deciders: product owner (request), Claude

## Context
Teams adopting the starter need realistic request data and a quick way to push load at their own endpoints without standing up k6.
A proposal (October 2026) sketched a separate starter: `@FakeData` / `@CustomFakeData` annotations over Datafaker, a Spring AI
`ChatClient` generator, a virtual-thread `RestClient` loop and an auto-configuration under `spring.loadtest.*`. The repository already
has the k6 generator (ADR-0022) and the CEL faker (ADR-0030) for *suite generation*; what is missing is an **in-process, embeddable**
generator that a host calls from its own code and tests.

## Decision
New opt-in module `spring-ai-mcp-server-common-mockdata` (plain Java, like `validation`), annotations in the existing `annotations`
module, Spring wiring in `autoconfigure` (`DaiMockDataAutoConfiguration`, `dynamic.ai.agent.mockdata.*`), in the BOM, optional
dependency of `autoconfigure`, not in the starter. Adds one third-party dependency: **Datafaker 2.7.0** (+ rgxgen, libphonenumber; snakeyaml
was already vendored) — 3.6 MB added to `offline-repo/`.

What was kept from the proposal: `@FakeData`, `@CustomFakeData` + `CustomFakerProvider`, Datafaker expressions, a Spring AI fallback,
virtual threads for load, properties-controlled and conditional beans. What was changed, and why:

| Proposal | Here | Reason |
|---|---|---|
| `spring.loadtest.*`, `org.example` packages | `dynamic.ai.agent.mockdata.*`, `com.springaimcpservercommon.mockdata` | namespace isolation rules (CLAUDE.md) |
| `spring-ai-starter-model-openai` and `spring-boot-starter-web` as hard dependencies | none: the AI is a port (`AiMockGenerator`); the Spring AI adapter is used only if the host has a `ChatClient.Builder` or `ChatModel`; no web dependency | a library must not force a provider or a web stack; any Spring AI model works |
| reflection engine for mutable classes with `parseInt` coercion | records (the normal DTO today), nested objects, collections, maps, `Optional`, enums, `java.time`, `UUID`, BigDecimal; lenient text-to-type conversion; per-field locale; seed | real DTOs; failures must not abort a load test |
| unannotated fields silently skipped | filled from field name and type (e-mail, names, phone, city, …) | "zero boilerplate" |
| any failure throws `RuntimeException` | a field the engine cannot produce keeps a placeholder and is reported as a `FieldGap` (logged once per field, never values) | a load test must keep running |
| `chatClient…entity(clazz)` for **every** mock | batched generation into a pool reused round-robin, per-run call budget, circuit breaker with cool-down, timeout | an LLM call per request is slow, costly and non-deterministic; 1 000 objects cost 1 call |
| AI as the engine *or* a switch | `FAKER` / `AI_FALLBACK` (default) / `AI`, plus `@FakeAi(hint)` for fields that need understanding; Datafaker annotations always win | cheap by default, AI where it adds value |
| `RestClient` loop, exceptions swallowed, no limits, results discarded | JDK `HttpClient` on virtual threads; every outcome counted (2xx/3xx/4xx/5xx/timeout/error class); latency percentiles; ramp-up; request-rate cap; cancel/progress; a report with counts only | a load generator that hides failures measures nothing |
| any URL, any environment | `SimulationGuard`: refused when production rules apply (PROD or undeclared tier, LLD-12), loopback + allow-listed hosts only, link-local/cloud-metadata addresses never, caps on users/duration/rate, http(s) only; simulator off by default | a load generator is a weapon; fail closed |
| controller in the sample | none in the library; the host triggers it (guide has a sample); an admin API is OQ-85 | admin endpoints belong to the control plane and its authorisation |

Not duplicated: k6 suite generation and API discovery stay in `loadtest` / `celfaker`; this module is for in-process use (tests, seeders, smoke load).

## Consequences
- Datafaker becomes a dependency of hosts that opt in (via the BOM entry); `offline-repo/` carries it.
- AI-generated samples are reused: variety of AI-filled fields is bounded by the batch size (default 20) while Datafaker-filled fields vary freely.
- Seeded runs are reproducible for single-threaded use; concurrent use shares one random source.
- The simulator measures from the generating process: client-side saturation (CPU, sockets) can cap results. For distributed or very high load use k6 (ADR-0022).
