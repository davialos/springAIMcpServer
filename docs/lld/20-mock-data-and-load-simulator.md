# LLD-20: Mock data engine and load simulator

Status: implemented v1. Module `spring-ai-mcp-server-common-mockdata` (plain Java), annotations in `…-annotations`, wiring in `…-autoconfigure`
(`DaiMockDataAutoConfiguration`), decision ADR-0031, guide `docs/integration/mockdata-guide.md`.

## 1. Purpose
Give a host (and its tests) populated DTOs and bounded synthetic load: `MockDataEngine.generate(Type.class)` and `LoadSimulator.run(spec)`.

## 2. Annotations (`annotations` module, zero dependencies)
| Annotation | Meaning |
|---|---|
| `@FakeData("name.fullName", locale = "")` | a Datafaker expression (the text inside `#{…}`); evaluated per element for collections |
| `@CustomFakeData(provider = X.class)` | value from a `CustomFakerProvider.provide(RandomGenerator)`; a bean of that type in the context wins over reflection |
| `@FakeAi("hint")` | needs a model: reported as a gap, filled from the AI pool; a placeholder without a model |
| `@FakeIgnore` | left at its default |
Targets are `FIELD` and `RECORD_COMPONENT` (both are read, so annotated records work).

## 3. Engine (`mockdata.engine`)
`DatafakerEngine.generate(type, overrides)` → `Generated<T>(value, gaps)`. Precedence per field: override (AI value by path) › `@FakeIgnore` › `@CustomFakeData` › `@FakeAi` › `@FakeData` ›
type/name heuristics. Heuristics: name contains email / firstName / lastName / name / username / phone / city / country / street / zip / state / company / url / iban / currency / title / password / ip / id / description…;
numbers by name (`age` 18–80, `quantity` 1–20, price-like 1–1000 with 2 decimals); enums, `UUID`, `java.time`, `URI`, `Locale`, `Optional`, arrays, collections (1..`maxCollectionSize`), maps, nested records/classes
(depth limit 4). Records through the canonical constructor; classes need a no-argument constructor (static, final and transient fields are skipped). `FieldGap.Reason`: `AI_REQUESTED`, `UNSUPPORTED_TYPE`,
`EXPRESSION_FAILED` (bad expression, or text not convertible to the type), `DEPTH_LIMIT`. Thread-safe; one `Faker` per locale sharing one seeded `Random`.

## 4. AI fallback (`mockdata.ai`)
`FallbackMockDataEngine(engine, pool, mode)`: generate; if gaps and a pool exist, take one AI sample, copy the values at the gap paths into overrides, generate again. Modes: `FAKER` (never ask), `AI_FALLBACK`, `AI` (the Datafaker engine
runs with `aiForUnannotated`, so every unannotated leaf is a gap). `AiMockPool`: per type a list of samples from **one** batched call (`batchSize`, default 20) handed out round-robin; budget `maxCalls` (default 50 per run);
circuit breaker (`failureThreshold` 3 → paused `cooldown` 1 min); never throws. Port `AiMockGenerator.generate(type, count, hints)`; the Spring AI adapter (`SpringAiMockGenerator`, in autoconfigure) sends a system prompt
(JSON array only, fictional data, types respected), the `ClassDescriber` text (field names, types, `@FakeAi` / `@FakeData` hints — no host data) and the paths that matter most, bounded by `ai.timeout`; the answer is parsed by
`AiResponseParser` (first JSON array, unknown properties ignored; errors never quote the answer). Logging: each (type, path, reason) once, no values.

## 5. Simulator (`mockdata.simulation`)
`LoadSimulator.start(spec)` → `SimulationHandle` (progress, `cancel()`, `result()`), `run(spec)` blocks. `SimulationSpec(target, method, headers, payload supplier, virtualUsers, duration, rampUp, maxRequestsPerSecond, requestTimeout)`.
One virtual thread per user on a `newVirtualThreadPerTaskExecutor`, JDK `HttpClient` (HTTP/1.1, no redirects, 5 s connect timeout); the executor stays open until every user is done (it also runs the client's own tasks).
Pacing: all users claim the next slot of one shared schedule (no catch-up bursts); ramp-up starts user *i* at `rampUp·i/N`. Each request: build (payload object → JSON, `String` as is), send, record latency in a lock-free
log-linear histogram (32 sub-buckets per power of two, ≈3 % resolution), count the outcome (`2xx 3xx 4xx 5xx timeout error:<Class> payload-error`), keep ≤ 5 distinct error samples (status codes / class names).
`SimulationReport`: requests, successes, failures, outcomes, elapsed, rps, p50/p90/p95/p99/max (µs), samples, cancelled. **No request or response data.**
`DefaultSimulationGuard`: production rules → refuse; http(s) only; link-local literals refused; loopback allowed (`allowLoopback`) else host must match `allowedHosts` (`*.stage.example.com`); `maxVirtualUsers` (200),
`maxDuration` (10 min), `maxRequestsPerSecond` (0 = no cap required; otherwise specs must declare a rate at or below it).

## 6. Auto-configuration (`dynamic.ai.agent.mockdata.*`)
`enabled` (true), `mode` (`AI_FALLBACK`), `locale` (`en`), `seed`, `ai.{batch-size,max-calls,failure-threshold,cooldown,timeout}`, `simulator.{enabled=false,allowed-hosts,allow-loopback,max-virtual-users,max-duration,max-requests-per-second}`.
Beans (all `@ConditionalOnMissingBean`): `MockDataEngine` (looks up an `AiMockGenerator` bean first, then a Spring AI `ChatClient.Builder` or a single `ChatModel`, only if Spring AI is on the class path),
`SimulationGuard` (tier from `EnvironmentSafetyPolicy` + `EnvironmentSignals` evaluated on every check; **absent environment beans = production = refuse**), `LoadSimulator` (only with `simulator.enabled=true`).

## 7. Failure modes
Model down / slow / wrong answer → circuit, placeholders, one WARN per field; budget exhausted → samples reused; unsupported type → gap, field left default; bad expression → heuristic value + gap;
guard refusal → `SimulationRefusedException` before anything is sent; target unreachable → counted as `error:<Class>`; cancelled → report with `cancelled=true`.

## 8. Tests
`DatafakerEngineTest` (records, classes, annotations, locale, seed, gaps, overrides, depth limit, 2 000 virtual-thread callers), `FallbackMockDataEngineTest` (one call for 1 000 objects, modes, circuit and cool-down,
budget, AI mode, parser, description), `LoadSimulatorTest` (against a real local server: payloads, latency, outcome kinds, connection errors and timeouts, rate cap, ramp-up, cancel, guard, histogram accuracy),
`DaiMockDataWiringTest` (Spring AI adapter through a stub `ChatModel`, host port, provider beans, simulator guard in DEV / PROD / no environment, limits from properties).
