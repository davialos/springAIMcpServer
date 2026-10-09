# LLD-19: CEL expression faker, API data generator and flow studio

Status: implemented v1. Module `spring-ai-mcp-server-common-celfaker` (developer tool; not in the starter or the BOM),
decision ADR-0030, builds on LLD-18 (rule engine) and LLD-16 (k6 load tests). Guide: `docs/integration/celfaker-guide.md`.

## 1. Purpose & responsibilities
1. Turn **JSON API payloads** into parameter-library candidates: `sysObject.sysObjectAttribute` + CEL data type.
2. The user **selects** which values to use; for them, **fake valid CEL expressions** with every operator and macro that fits the type.
3. Produce an **attribute map** (JSON file) of valid / boundary / invalid values per parameter, and an **input → result case
   matrix** for each expression computed by the CEL runtime.
4. Generate **request data** for the user's APIs (valid, rule-satisfying, negative) and a **k6 workflow suite** that calls the APIs,
   passes values from one response into later requests, calls the user's validation APIs, and checks rejection of invalid data.
5. A **dashboard** to author the flow by drag and drop.

Not owned: running k6, storing results, authentication of the system under test beyond header injection (`{{env.TOKEN}}`).

## 2. Pipeline
```
 ApiContract (apis, docs, examples, rules, validation apis)
   │ PayloadAnalyzer          body JSON → Candidate(path, objectCode, attributeCode, DataType, sample)   [payload]
   ▼
 selected candidates ──► FakerLibrary → ParameterLibrary (real CEL environment, macros on)
   │ ValueFactory (seed)      → AttributeValueMap  ◄── user-edited attribute-map.json wins              [values]
   │ ExpressionFaker          → GeneratedExpression[] (+ rejected)                                       [expr]
   │ CaseBuilder              → CelCase[] (inputs, expected true|false|error)                            [expr]
   │ DataGenerator            → ApiData(valid[], invalid[], warnings) per API                            [data]
   ▼ WorkflowPlanner (order / validate / propose)                                                        [workflow]
 K6WorkflowGenerator ──► k6/{main.js, lib/runtime.js, workflow.json, apis.json, data/*.json, README.md}  [k6]
```
`FakerPipeline.run` composes the steps and returns `files` (relative path → text); the CLI writes them, the dashboard zips them.
Pure and deterministic for a seed (same input ⇒ identical files; asserted by `PipelineTest`).

## 3. Parameters from payloads (`payload`)
- Sys object = the enclosing object (nested objects joined by `_`: `customer_address.city`); top-level scalars belong to the API's
  `sysObject` (default: the API id). Attribute = property name (non-identifier characters → `_`; CEL reserved words get a trailing `_`).
- Type inference: integral number → INT (beyond 64 bit → DOUBLE), other number → DOUBLE, boolean → BOOL, ISO instant string →
  TIMESTAMP (normalised to UTC), ISO-8601 duration string → DURATION, string → STRING, array of strings / ints / numbers →
  LIST_STRING / LIST_INT / LIST_DOUBLE, object → descended into (or one MAP when listed in `mapPaths` or empty), null / empty array →
  ANY. Arrays of objects and mixed arrays are skipped with a reason (shown as a warning).
- The union across APIs is the library; the same CEL name with two types keeps the first and warns.

## 4. Expression faker (`expr`)
Per type, each template is a (category, CEL text, meaning) draft; every draft is compiled and only compiled ones are kept.
| Type | Operators / functions / macros exercised |
|------|-------------------------------------------|
| INT, DOUBLE | `== != < <= > >=`, range with `&& ||`, `!`, `in`, `+ - * / %` (int), unary `-`, `?:`, `exists / all / exists_one / filter / map` over literal lists, `int() double() string()`, `type()`, `dyn()` |
| STRING | comparisons, `size`, `startsWith endsWith contains`, `matches` (kind-aware: e-mail, UUID, URL, phone, date), `+`, `in`, macros over literal lists (injection-character screen), `bytes() string() int() double()` of numeric text, `?:`, `type()` |
| BOOL | `p`, `!p`, `&& \|\|`, `==`, `?:`, `string() bool()`, macros over `[true,false]` |
| TIMESTAMP | comparisons, window checks, `getFullYear getMonth getDate getDayOfMonth getDayOfWeek getDayOfYear getHours getMinutes getSeconds getMilliseconds` (with and without time zone), `± duration`, `timestamp - timestamp`, `int() string() timestamp()` round trips, `in`, macros |
| DURATION | comparisons, `± duration`, `getHours getMinutes getSeconds getMilliseconds`, `string() duration()`, `in`, macros |
| LIST_* | `size`, `in`, indexing, `+`, `==`, `exists all exists_one filter map` with element predicates, `type()` |
| MAP | `in` key test, `has(m.k)`, `m.k`, `m["k"]`, `size`, `exists all exists_one filter map` over keys, `type()` |
| ANY | `==`, `!=`, `null` tests, `type()`, `dyn()` |
| cross-parameter | same-type comparisons (`a > b`, `a.contains(b)`, `a < b` for timestamps …), `double(int) > double`, and random `&&`, `||`, implication, ternary, `(a && b) || c` over single-parameter expressions of different parameters |
Options: categories, `maxPerParameter` (round-robin across categories), number of cross-parameter expressions. Seeded.

## 5. Values and cases (`values`, `expr`)
- `attribute-map.json` (`AttributeValueMap`, version 1): `attributes[name] = {type, kind, sample, valid[], boundary[], invalid[]}`.
  Kinds (EMAIL, UUID, URL, PHONE, DATE, NAME, CITY, CODE, GENERIC) pick realistic generators; TIMESTAMP/DURATION values are ISO-8601
  strings (what `Facts` binds). `valid` feeds happy-path data, `valid ∪ boundary` feeds CEL cases, `invalid` feeds negative data.
- `CaseBuilder.build(expression, max)`: cartesian product of the referenced parameters' inputs when ≤ `max`, otherwise a rotation that
  uses every value at least once then seeded random combinations; each combination is bound through `Facts` and evaluated by the
  program; result `true` / `false` / `error` (type mismatch, runtime error, non-bool).

## 6. API data (`data`)
- `valid[]` (default 30): per parameter a shuffled pool of valid values, cycled; when the API lists `rules`, parameters they reference
  take values from combinations that make **all rules true**. No such combination ⇒ warning, rules ignored for valid data.
- `invalid[]`: per parameter up to three wrong-type/null values and one *missing field*; per rule up to six combinations that make it
  **false** (`reason: "rule:<cel>"`). Each carries `expectedStatus` (the API's `invalidStatus`, default 400, 422).

## 7. Workflow and k6 (`workflow`, `k6`)
- `Workflow{name, steps[], load{profile,vus,duration,negatives}}`; `Step{id, api, dependsOn[], extract[], inject[], expectStatus[], thinkTime, x, y}`.
- `extract.from`: `body.<path>`, `header.<Name>`, `status`. `inject.target`: `path.<n>`, `query.<n>`, `header.<N>`, `body.<path>`.
  Values may contain `{{step.var}}`, `{{env.NAME}}`, `{{iter}}`, `{{vu}}`, `{{uuid}}`, `{{timestamp}}`; a value that is exactly one placeholder keeps its JSON type.
- `WorkflowPlanner.validate` reports: unknown API/step, duplicate id, cycle, bad extract/inject syntax, `{{x.y}}` where step `x`
  does not run before or does not extract `y`, path parameter without an injected value. `propose` chains the actions in contract order
  and wires validation APIs (id from the action's response example into the path).
- Generated suite: scenario `flow` (profile `smoke` 1 iteration | `load` | `stress` | `spike` | `custom`; ramping VUs scaled from `vus`, `duration`),
  scenario `negative` (`shared-iterations`, ≤ `negatives` cases thinned evenly; each case first runs its ancestor steps with valid data).
  Thresholds: `checks`, `workflow_ok` > 99 %, `http_req_failed{kind:flow}` < 1 %, p95 `http_req_duration{kind:flow}` < 1500 ms. Expected statuses are
  declared to k6 per request (`http.expectedStatuses`) so a correctly rejected negative request is not counted as a failure.
- `lib/runtime.js` is the single implementation of flow semantics (dependency-injected `http`, `check`, `sleep`).

## 8. Dashboard (`server`, UI `META-INF/resources/celfaker/ui/`)
Tabs: **APIs** (contract editor, docs, rules) → **Parameters** (tick payload values; `sysObject.attribute`, CEL type, sample) →
**CEL expressions** (generate, filter by category, "inputs → results" matrix, "+ rule" into an API) → **Attribute map** (edit valid / boundary / invalid) →
**Flow designer** (drag APIs onto the canvas, drag from a node's right dot to another node to run it after, inspector for
extract / inject / auto-wire, live validation, keyboard alternative: "Runs after" checkboxes, Delete key) → **Generate** (summary, file
preview, `.zip`). Endpoints (all JSON, stateless): `POST /api/analyze | attribute-map | expressions | cases | workflow/propose | workflow/validate | generate | generate.zip | import/curl | import/openapi | fake | send`, `GET /api/example`.
Hardening: loopback bind, `Host` allow-list, `application/json` required, 8 MiB body cap, caps on `validCount` (1000) and `casesPerExpression` (100), no `innerHTML` with data (text nodes only), CSP `default-src 'self'`.

## 8a. Adding APIs: cURL, Swagger / OpenAPI, fake input, send (`importer`)
- **cURL** (`CurlParser`): tokenizes like a shell (quotes, `$'…'`, line continuations); reads `-X`, `-H`, `-d/--data*`, `--json`, `-G`, `-u`, `--url`;
  ignores transport flags. URL → base URL + path (+ query). A JSON body becomes `requestExample` (non-JSON bodies are dropped with a warning).
  **Secrets are never kept:** `Authorization`, `Cookie`, `X-Api-Key`, `Api-Key`, `X-Auth-Token`, `X-Access-Token` values and `-u` credentials become `{{env.NAME}}` placeholders (a warning names each).
- **Swagger / OpenAPI** (`SpecFetcher`, `OpenApiImporter`): Swagger 2 and OpenAPI 3, JSON or YAML, from a URL (the service root, its Swagger UI or the document — the
  usual locations are tried: `/v3/api-docs`, `/v2/api-docs`, `/swagger.json`, `/openapi.json|yaml`, …) or pasted. Per operation: id (`operationId` or method+path), name/description
  from `summary`/`description`, path with the server's base path, required query parameters appended with example values, request/response example bodies built from the
  schemas (`example` > `default` > first `enum` > a value that satisfies type, format, bounds; `$ref`/`allOf`/`oneOf` resolved, recursion cut at depth 8, `readOnly` skipped),
  accepted 2xx statuses, rejection statuses (declared 400/422), auth header placeholders from `security` (`Bearer {{env.TOKEN}}`, `{{env.API_KEY}}`, `Basic {{env.BASIC_AUTH}}`),
  GET operations whose path ends in validate / verify / check become VALIDATION APIs, and **CEL rules from schema constraints** (`minimum`/`maximum`/exclusive bounds, `minLength`/`maxLength`,
  `pattern` → `matches`, `enum` → `in`, `minItems`/`maxItems`) named exactly like the payload analyzer names parameters, so valid fake data satisfies them and negative data violates them.
- **Fake input** (`FakeInput`): one API on its own — analyze its body, build values, solve its rules, return valid and invalid bodies. Rules that share parameters are solved
  together, independent groups separately (a joint search over every body parameter almost never satisfies all rules at once).
- **Send** (dashboard only): a REST-client style request to the service under test (`{{env.NAME}}` replaced from an in-memory environment box, never persisted; no redirects; 15 s;
  64 KiB answer cap; only absolute `http(s)` URLs). The response can become the API's `responseExample`.
- Limits: multipart/form bodies, GraphQL and non-JSON bodies are not faked; required/optional is not derived from the schema (OQ-83).

## 8b. Local-dev integration (ADR-0029)
`devctl` (local-dev/, macOS, process-compose) registers the faker as a built-in `command` service: command `PATH="$JAVA_HOME/bin:$PATH" exec ./scripts/celfaker.sh serve --port <port>`
in the checkout, readiness probe `GET /api/example` (120 × 5 s: first start compiles), environment `JAVA_HOME` (`auto:25`), `CELFAKER_SERVICES` (JSON `[{name,url}]` of the configured services, shown as import chips via
`GET /api/local-services`) and `CELFAKER_FRAME_ANCESTORS` (the dashboard's loopback origins; anything but a loopback `http(s)` origin is refused at start-up; default CSP stays `frame-ancestors 'none'`).
The service list is refreshed whenever devctl regenerates the project (`devctl sync`).

## 9. Failure modes
- Expression rejected by the checker → listed in `rejected`, never emitted. Rule that does not compile → warning, rule skipped.
- Workflow not runnable → generation refuses (`IllegalArgumentException`, HTTP 400 with the list of problems).
- A step whose response lacks an extracted value fails the iteration (logged without values); k6 `workflow_ok` drops.
- Resource limits: expression ≤ 8192 characters, comprehension iterations ≤ 10 000 (rule-engine CEL options).

## 10. Tests
Java: `ExpressionFakerTest` (types, 400+ expressions all compile, determinism), `PipelineTest` (linked files, rule-satisfying/violating data, workflow validation),
`DashboardServerTest` (API, ZIP, Host/Content-Type guards). Node (`scripts/celfaker-ui-test.sh`): flow model and `runtime.js` against an in-memory service.
Manually verified with real k6 1.3 against a mock service (load profile, 4 VUs, flow + 60 negative iterations, all checks green) and in headless Chromium (drag and drop, connect, generate).
