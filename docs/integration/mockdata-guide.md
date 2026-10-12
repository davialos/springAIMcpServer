# Mock data and load simulator — host guide

Annotation-driven mock objects (Datafaker), an optional AI fallback and a bounded virtual-thread load generator. Design: LLD-20, ADR-0031.

## 1. Add it
```xml
<dependency>
  <groupId>com.springaimcpservercommon</groupId>
  <artifactId>spring-ai-mcp-server-common-mockdata</artifactId>   <!-- version managed by the BOM -->
</dependency>
```
With the starter on the class path the auto-configuration (`dynamic.ai.agent.mockdata.*`) wires itself; the annotations come from `…-annotations`.

## 2. Annotate a DTO (records or classes)
```java
public record UserRegistration(
    @FakeData("name.fullName") String fullName,
    @FakeData("internet.emailAddress") String email,
    @FakeData("number.numberBetween '18','65'") int age,
    @CustomFakeData(provider = SecurityTokens.class) String internalSecurityToken,
    @FakeAi("a friendly one-sentence bio") String bio,      // AI where understanding helps
    @FakeIgnore String internalNote,
    String city,                                             // unannotated: filled from name and type
    List<String> tags) {}

public class SecurityTokens implements CustomFakerProvider {  // a bean of this type is used if present
  public Object provide(RandomGenerator random) { return "tok-" + Long.toHexString(random.nextLong()); }
}
```

## 3. Generate
```java
@Autowired MockDataEngine mocks;
UserRegistration one = mocks.generate(UserRegistration.class);
List<UserRegistration> many = mocks.generate(UserRegistration.class, 10_000);
```
Fields the engine cannot produce keep a placeholder and are logged once (`Mock data for X.y is a placeholder (…)`).

## 4. Turn the AI on (optional)
Have a Spring AI model (`ChatClient.Builder` or a single `ChatModel`) or declare your own `AiMockGenerator` bean. Then `@FakeAi` fields and anything Datafaker cannot fill come from the model — **one batched call per type**
(`ai.batch-size`, default 20), reused round-robin, within `ai.max-calls`, paused by a circuit breaker after repeated failures.
```yaml
dynamic:
  ai.agent.mockdata:
    mode: AI_FALLBACK        # FAKER = never ask the model · AI = the model fills every unannotated field
    locale: de
    seed: 42                 # reproducible text values (single-threaded)
    ai: { batch-size: 20, max-calls: 50, timeout: 60s }
```

## 5. Simulate load (opt-in, never in production)
```yaml
dynamic.ai.agent.mockdata.simulator:
  enabled: true
  allowed-hosts: ["*.stage.example.com"]     # localhost is always allowed; anything else must be listed
  max-virtual-users: 200
  max-duration: 10m
  max-requests-per-second: 0                 # >0 forces every spec to declare a rate at or below it
```
```java
@Autowired LoadSimulator simulator; @Autowired MockDataEngine mocks;

SimulationReport r = simulator.run(new SimulationSpec(URI.create("http://localhost:8080/api/v1/ingest"), "POST",
    Map.of("Authorization", "Bearer " + token),
    () -> mocks.generate(UserRegistration.class),        // a fresh payload per request
    200, Duration.ofSeconds(60), Duration.ofSeconds(10) /* ramp-up */, 500 /* req/s cap */, Duration.ofSeconds(5)));
// r.requests(), r.errorRate(), r.outcomes() {2xx=…, 4xx=…, timeout=…}, r.p95Micros() …  (counts only, never data)
```
`start(spec)` returns a handle (`requestsSoFar()`, `cancel()`, `await()`) for long runs. A refusal (`SimulationRefusedException`) says which rule: production or undeclared environment tier (declare
`dynamic.ai.agent.environment.tier`), host not allow-listed, link-local address, users / duration / rate above the caps.

Triggering it from HTTP is up to you (keep it behind your own authorisation and out of production); a minimal controller:
```java
@PostMapping("/internal/loadtest/run")
SimulationReport run() { return simulator.run(SimulationSpec.post(URI.create(target), () -> mocks.generate(UserRegistration.class), 50, Duration.ofSeconds(30))); }
```

## 6. When to use something else
Distributed or very high load, scripted multi-step journeys, Grafana dashboards: the k6 tooling (`docs/integration/load-testing-guide.md`, the CEL faker's workflow studio). This module is for in-process generation, seeders and smoke load.
