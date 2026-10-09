# ADR-0030: CEL expression faker, data generator and flow studio
- Status: Accepted
- Date: 2026-10-08
- Deciders: product owner (request), Claude

## Context
The rule engine (ADR-0025) type-checks CEL expressions against a **parameter library** — every `sysObject.attribute` is a typed
CEL variable. The load-test generator (ADR-0022) produces k6 suites from discovered APIs. Teams that adopt both need the
bridge: take the APIs they already have (payloads, documentation, their "validate" endpoints), turn payload values into
parameters, get valid CEL expressions to author rules from, and get *data* — valid, rule-satisfying and deliberately invalid —
to drive the k6 tests of those same APIs, including multi-call flows where one response feeds the next request.

## Options considered
1. **Extend the `loadtest` module.** It is Spring-source driven (discovery) and has no CEL dependency; pulling CEL (protobuf, Guava)
   into it would burden every load-test user, and it would make the load-test module own rule semantics.
2. **Extend the `ruleengine` module.** It must stay a runtime library (no HTTP server, no k6, no UI).
3. **New developer-tool module `spring-ai-mcp-server-common-celfaker`** depending on `ruleengine` (for the real CEL checker and
   runtime) and Jackson only; not in the starter, not in the BOM. *(chosen)*

For the flow designer: a React/Angular app (build step, npm), or **plain ES modules served by a loopback JDK `HttpServer`**
like the chat UI (ADR-0023 precedent: no build step, no runtime npm dependencies). *(chosen)*

For executing flows: generate a k6 script containing the flow logic inline, or **a small shared `runtime.js`** (dependency-injected
`http`/`check`/`sleep`) that the generated `main.js` imports. The runtime is plain synchronous JS, unit-tested in Node with an
in-memory HTTP double and exercised for real with k6. *(chosen — one owner of the flow semantics)*

## Decision
- New module `celfaker` (package `com.springaimcpservercommon.celfaker`), no Spring.
- **Valid by construction:** every faked expression is compiled by `ParameterLibrary.compileBoolean` before it is returned;
  what the checker refuses is reported (`rejected`), never emitted. Input/result cases are computed by the real CEL runtime
  (`CaseBuilder`), never predicted.
- The rule engine's CEL environments now enable the **standard macros** (`has`, `all`, `exists`, `exists_one`, `map`, `filter`).
  They were silently disabled (`standardCelBuilder()` without `setStandardMacros`), so no rule could use a comprehension.
  LLD-18 §5 is updated in the same change. Comprehension cost stays bounded by `comprehensionMaxIterations` (10 000).
- **Attribute map** (`attribute-map.json`): per parameter `valid`, `boundary` and `invalid` values; generated deterministically for a
  seed, hand-editable, and reloaded by every later step — this is the link between parameters and the data used against APIs.
- **Rules drive data:** an API's CEL `rules` are evaluated over the attribute map; valid bodies use inputs that satisfy all rules,
  negative bodies use inputs that violate one (plus wrong-type / null / missing-field mutations).
- **Workflow** = steps (API calls) + `dependsOn` edges + `extract` (response → variable) + `inject` (`{{step.var}}` → path / query /
  header / body). Validation APIs are ordinary steps with an `expectBody` subset check. Two k6 scenarios: `flow` (valid data, load
  profile) and `negative` (each invalid body after its prerequisite steps; must be rejected with the API's `invalidStatus`).
- **Dashboard** (`celfaker serve`) binds to the loopback interface only, checks the `Host` header against loopback names and
  requires `application/json` on POST (no CORS answers), is stateless (the browser holds the project in `localStorage`, as a
  convenience only), and has no authentication because it exposes nothing but pure functions of the posted input.
- The module is a developer tool: never on a host's runtime classpath; no new third-party dependency (offline repo unchanged).

## Consequences
- Rule authors get a catalogue of known-valid CEL per data type to start from; the same catalogue is a regression corpus for the
  CEL environment itself (the faker test fails if the checker stops accepting a category).
- Enabling macros widens what a stored rule can do; it does not change existing rules. Rules now may loop over lists up to the
  10 000-iteration cap.
- Negative-data expectations ("must be 400/422") are conventions the user can override per API (`invalidStatus`) — the faker cannot
  know which fields an API treats as optional. Cases that the API legitimately accepts show up as failed checks and are edited in
  the generated `*.invalid.json` or, better, in the attribute map.
- Generated data is only as realistic as the attribute map; ids that must exist in the system under test (e.g. a customer id) are
  supplied by workflow injection (`body.customerId = {{customer.id}}`) or by editing the map.

## Addendum 2026-10-09: importing APIs and fake input
The dashboard can add APIs from a cURL command, a Swagger 2 / OpenAPI 3 document (URL or pasted, JSON or YAML) and fake input for one API on its own, and can send a request to the
service under test (LLD-19 §8a). Decisions: (a) the importer lives in the same module (`importer` package; only new dependency is `jackson-dataformat-yaml`, already vendored for the
load-test module); (b) imported secrets are replaced by `{{env.NAME}}` placeholders and the dashboard's environment box is in-memory only; (c) the `send` and `import/openapi` endpoints make
outbound HTTP calls to an address the developer types — acceptable for a loopback developer tool guarded by the Host / Content-Type checks, and limited to `http(s)`, no redirects on send, 10 MiB / 64 KiB caps.

## Addendum 2026-10-09 (2): scenarios and the in-browser runner
Scenario testing needed (a) several named workflows, (b) per-step assertions and body sources (generated / invalid case / custom), and (c) running them from the dashboard. To keep **one owner of the
flow semantics** the runtime (`runtime.js`) became asynchronous and moved next to the UI modules; k6 runs it (async scenario functions are supported by k6) and the dashboard runs the same file through
an `http` adapter over `/api/send`. Rejected alternatives: a second executor in Java (two implementations to keep in step), and calling the service from the browser (CORS). Verified with k6 1.3 (58k iterations,
all checks) and in headless Chromium (chained scenario, rejection scenario, failing assertion).
