# LLD-02: Semantic Annotations & Runtime Metadata Extraction

| Field | Value |
|-------|-------|
| Status | Draft v2 (2026-09-28: switched from build-time Javadoc capture to runtime annotations — ADR-0013 supersedes ADR-0003) |
| Owner agent | metadata-extraction-designer |
| Module(s) | `annotations`, `core` (scanner port, model), `jpa` (entity scanner), optional `javadoc-enricher` (v1.x) |
| Related features | F-02, F-03, F-05, F-10, F-11, F-15 |
| Related ADRs | ADR-0013, ADR-0014 |

## 1. Purpose & responsibilities
Learn what the host application *means* — entities, fields, services, actions, and their
plain-English semantics — from **explicit, runtime-retained annotations** that host developers put on
their code, scanned once at startup. No build plugin, no Javadoc parsing, identical under Maven,
Gradle, Bazel, or an IDE build. The opt-in model is deterministic: **nothing is visible to AI unless a
developer annotated it**, and nothing becomes writable unless a developer explicitly said so.

Does not decide who may use an element (SEC-01) or override its semantics at runtime (LLD-03 §4).

## 2. Annotation suite (`annotations` module, zero dependencies, `@Retention(RUNTIME)`)
Names follow the product owner's design note (2026-09-28); attributes added where the rest of the
design needs them are marked ➕.
```java
// design sketch
@Target({TYPE, METHOD}) @Retention(RUNTIME) @Documented
public @interface AiContext {
    String description();                                   // mandatory plain-English meaning for the LLM
    String[] keywords() default {};                         // domain terms / synonyms (also used by tool search)
    String name() default "";                               // ➕ stable logical name; default = simple name
    Classification classification() default Classification.INTERNAL;   // ➕ drives ABAC & masking
}

@Target({FIELD, RECORD_COMPONENT, METHOD /* getter */}) @Retention(RUNTIME)
public @interface AiEntityProperty {
    String meaning();                                       // what the column represents in the real world
    boolean sensitive() default false;                      // true ⇒ value never sent to the LLM, never shown, masked everywhere
    boolean writable() default false;                       // ➕ may appear as an editable field in a reviewed write (LLD-11)
    Classification classification() default Classification.INHERIT;     // ➕
}

@Target(METHOD) @Retention(RUNTIME)
public @interface AiExposedAction {
    String intent();                                        // what the action accomplishes, for the LLM
    boolean readOnly() default true;                        // SAFE DEFAULT; false ⇒ proposal-only write (LLD-11, ADR-0009)
    String name() default "";                               // ➕ tool name; default = snake_case(method)
    boolean idempotent() default false;                     // ➕
    String[] keywords() default {};                         // ➕
}

@Target(PARAMETER) @Retention(RUNTIME)
public @interface AiParam {                                 // ➕
    String description();
    String name() default "";                               // needed only if compiled without -parameters
    boolean required() default true;
    boolean sensitive() default false;                      // value redacted in traces/audit
}

@Target(TYPE) @Retention(RUNTIME)
public @interface AiQueryConstraints {
    int maxLimit() default 50;                              // max rows per AI/dynamic query on this entity (LLD-05 §5a)
    String[] mandatoryFilters() default {};                 // attributes that must be constrained, bound server-side (LLD-05 §5a)
}
enum Classification { INHERIT, PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
```
The annotations module is the only compile dependency the host's domain code needs; it contains no
Spring types, so it can live in shared domain/API jars.

### Where each annotation applies
| Host element | Annotations | Becomes |
|--------------|-------------|---------|
| JPA `@Entity` class | `@AiContext` (+ `@AiQueryConstraints`) | Catalog entity (queryable once a QueryDefinition publishes it) |
| Entity field / record component / getter | `@AiEntityProperty` | Catalog attribute. **Unannotated attributes of an annotated entity are not exposed** by default (§4) |
| Spring bean class (service) | `@AiContext` | Context for its actions (tool-group description) |
| Public method on a Spring bean | `@AiExposedAction` (+ `@AiParam` on params) | Catalog operation → tool candidate (LLD-07) |
| `@RestController` class/method | `@AiContext` only | **Descriptive context only**, never a tool: controllers take HTTP-bound arguments and sit outside the service boundary. Put `@AiExposedAction` on the service method the controller calls |
| DTO / record used in signatures | `@AiEntityProperty` on components (optional) | JSON-schema descriptions |

## 3. Runtime scanning
### 3.1 When
`SmartInitializingSingleton` in our core auto-configuration — after all singletons exist, before
`ApplicationReadyEvent`, **exactly once per context**. Not `ContextRefreshedEvent`: it fires again on
every refresh, in child contexts, and on devtools restarts, which would rebuild the graph mid-traffic.

### 3.2 What is scanned (and what is not)
| Source | How | Guards |
|--------|-----|--------|
| Beans | `beanFactory.getBeanDefinitionNames()` → `beanFactory.getType(name, false)` → `ClassUtils.getUserClass(type)` | Never instantiates lazy beans or FactoryBeans; skips our own beans and `ROLE_INFRASTRUCTURE` definitions |
| Package scope | Only types under `dynamic.ai.agent.scan.base-packages`; default = `AutoConfigurationPackages.get(beanFactory)` (the `@SpringBootApplication` package) | Annotated classes in third-party jars outside the base packages are ignored — a dependency cannot silently expose itself (SEC-02 T3) |
| Methods | `MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY)` on public methods | Finds annotations declared on interfaces too; bridge/synthetic/static methods rejected |
| Entities | `EntityManagerFactory.getMetamodel().getEntities()` → Java type → field/getter annotations | Entities are not beans, so the metamodel is the source. Multiple EMFs supported (each tagged) |
| Parameter names | `@AiParam(name)` ?? `Parameter.getName()` (needs `-parameters`; Spring Boot's Maven and Gradle plugins enable it by default) | If names resolve to `arg0…` and no `@AiParam(name)` → action excluded + startup issue |

### 3.3 Produced model (immutable)
```java
// design sketch
public record ScannedCatalog(String scanFingerprint, Instant scannedAt, String hostVersion,
        Map<CatalogElementRef, EntityDescriptor> entities,
        Map<CatalogElementRef, OperationDescriptor> operations,
        Map<CatalogElementRef, ContextDescriptor> contexts,   // service/controller @AiContext
        List<ScanIssue> issues) {}
```
`CatalogElementRef` examples (stable across restarts while the signature is unchanged):
`entity:com.acme.order.Order`, `attr:com.acme.order.Order#status`,
`op:com.acme.order.OrderService#findRecentOrders(java.lang.Long,int)`.
`scanFingerprint` = SHA-256 over the canonical descriptor list; published resources pin it for drift
detection (LLD-03 §6).

### 3.4 Type → JSON schema
Reuse Spring AI's `JsonSchemaGenerator` for method input schemas (the generator `ToolDefinitions`
uses), then decorate with `@AiParam`/`@AiEntityProperty` descriptions and remove sensitive members.
Return types use our own mapper (records, POJOs, enums, `java.time`, collections, `Optional`, cycle-safe `$ref`).

## 4. Visibility & safety rules
| Rule | Default | Rationale |
|------|---------|-----------|
| Entity visible only with `@AiContext` | on | Opt-in |
| Attribute visible only with `@AiEntityProperty` | on (`scan.expose-unannotated-attributes=false`) | Every exposed column has an explicit meaning; forgotten columns stay hidden |
| `sensitive=true`, `@JsonIgnore`, `@Transient` | never exposed; value masked in every output | LLD-12 §9 |
| Sensitive-name heuristic (`password, secret, token, apiKey, credential, ssn, iban, cardNumber, cvv, pin`, configurable) on an attribute with `sensitive=false` | exposed only if listed in `dynamic.ai.agent.scan.confirm-sensitive-names`; else excluded + issue | Guards against a copy-pasted `sensitive=false` |
| Action visible only with `@AiExposedAction` | on | Opt-in |
| `readOnly=false` actions | registered as **proposal-only** tools (never executed by the model) | ADR-0009 |
| `readOnly=true` action on a method whose effective `@Transactional` is read-write | kept, flagged `READ_ONLY_ACTION_IN_WRITE_TX`; the runtime write guard enforces read-only (ADR-0014) | Class-level `@Transactional` on read methods is common; enforcement belongs at runtime, not in a heuristic |
| List-returning action without `Pageable`/`Limit`, `Page`/`Slice`/`Window` return, or an `@AiParam` limit | issue `UNBOUNDED_LIST_ACTION`; excluded when `scan.strict=true` | Truncating after the call doesn't prevent the heap allocation (LLD-14 §3.3) |
| Tool parameters nested deeper than 2, maps, polymorphic types | issue `COMPLEX_TOOL_ARGS` (warning) | Flat args give fewer malformed calls (LLD-14 §3.2) |
| > 8 actions on one entity | hint `CONSIDER_OUTCOME_ACTION` | Fewer model round trips (LLD-14 §3.1) |
| Description length | `description`/`intent` ≤ 1 024 chars, `meaning` ≤ 256; secret-pattern scan | Prompt budget; no secrets in prompts |

## 5. Build-tool-agnostic CI checks (replace the build plugins)
- **Catalog export**: `GET {base}/admin/api/v1/catalog:export` (DEV/TEST only) and a test-kit helper
  `DynamicAiCatalogAssert.exportCatalog(context)` produce canonical JSON.
- **Golden-file test** in the host's normal test suite (JUnit, any build tool): export → compare with
  `src/test/resources/ai-catalog.golden.json` → diff report of added/removed/changed actions, flagging
  breaking changes (removed action, changed signature, `readOnly` flipped to `false`, sensitivity lowered).
- `dynamic.ai.agent.scan.strict=true` (recommended in CI) turns scan issues into startup failures.

## 6. Optional Javadoc enrichment (v1.x, `javadoc-enricher` module)
The former build-time processor (ADR-0003) survives only as an **optional enricher**: when present it
contributes `@param`/`@return` text where `@AiParam.description` is missing. Annotations always win.
Nothing in the core depends on it.

## 7. Failure modes
| Failure | Behavior |
|---------|----------|
| Scan throws for one bean/entity | Skip that element, record `ScanIssue`, continue (LLD-12 §4: fail the feature, not the host) |
| No annotated elements | Empty catalog, INFO log; agents run with no host tools |
| Duplicate tool name | Both excluded + issue naming both methods (no silent winner) |
| Parameter names unavailable | Action excluded + issue (fix: `-parameters` or `@AiParam(name)`) |
| Annotated method not on a Spring bean | Ignored + issue (tools must go through bean proxies, ADR-0008) |

## 8. Performance
Reflection over beans in the base packages only; target < 300 ms for 2 000 beans/entities; cached for
the context's lifetime. No classpath scanning — the bean factory and the metamodel already know every relevant type.

## 9. Test strategy
Fixture host apps covering: interface-declared annotations, CGLIB and JDK proxies, records, generics,
lazy beans (must stay uninitialised), multiple EMFs, missing `-parameters`, duplicate names, the
sensitive-name heuristic, an annotated third-party jar outside the base packages (must be ignored), and a
Kotlin host (runtime annotations work unchanged — no KSP needed).

## 10. Open questions
OQ-06 (Spring Data repository methods as actions), OQ-20 (`expose-unannotated-attributes` default).
