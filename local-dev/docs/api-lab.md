# API Lab: AI-designed test data, API threads and workflows

The **API Lab** is a separate window of the devctl dashboard. Open it with **API Lab ↗** in the dashboard header,
or go to <http://127.0.0.1:8765/lab>. Point it at a repository or a running service and it will:

1. **Discover the APIs.**
   - From the running service's OpenAPI (`/v3/api-docs`).
   - From the **Java sources** (Spring `@RestController`s, `@RequestBody` DTOs as records or classes, `@PathVariable`,
     `@RequestParam`, `@RequestHeader`, enums, and the validation annotations `@NotNull`, `@Email`, `@Size`,
     `@Min`/`@Max`, `@Positive`, `@Pattern`, `@Past`/`@Future`).
     **API-first projects** are covered too: controllers that implement OpenAPI-Generator interfaces, generated sources
     under `target/`/`build/`, mapping paths given as constants, and constraints on getters.
   - From `/actuator/mappings`.
2. **Suggest threads.** A thread means "this response attribute of API A feeds that request attribute of API B", for
   example `POST /customers → id` ⟶ `POST /orders body.customerId`. Each suggestion has a confidence and a reason.
3. **Let the AI decide.** Your coding agent (MCP) or the built-in assistant accepts the true threads, rejects the
   wrong ones and adds missing ones, as a domain expert would. The heuristics only provide the starting point.
4. **Generate realistic fake payloads.** Values come from each field's name, format, constraints and enum, plus rules
   the AI or you set per field. Linked attributes are filled from real responses.
5. **Build workflows.** Steps are ordered so producers run before consumers, and every thread is bound. The AI can
   instead design the real business flow.
6. **Run workflows** against the local service. Every request and response is stored, and real ids are captured
   into a pool for later data.
7. **Export a workflow as a k6 load test.** The threads are resolved per virtual-user iteration. The load test runs in
   devctl's load-test pipeline, with JFR, live metrics and a pass/fail per threshold.

All of it is **plain JSON in folders** on your machine, so you can read, diff, edit or commit it.

```
~/localdev/apilab/<project>/          (config key apilab_dir)
  project.json        repo, service, base_url, default headers (e.g. auth), discovery source
  apis/<api>.json     method, path, request fields (path/query/header/body + types/constraints), response fields
  links.json          threads: {from: {api, field}, to: {api, field}, status, confidence, source, reason}
  rules/<api>.json    fake-data rule per request field
  data/<api>.json     generated payloads
  workflows/<id>.json steps + bindings
  runs/<run>.json     executed workflows: every request/response, bound values, timings
  pool.json           values captured from real responses (ids, codes) - reused by generated data
  java-suite/         optional: the repository's full Java k6 generator output (DB-backed values, journeys)
```

## The window

| View | What you do there |
|---|---|
| **Threads** | API cards are laid out in producer → consumer columns. Blue curves are accepted threads; orange ones are suggestions (filter by confidence). Hover a thread for its reason, and click it to accept, reject or delete. **Link by hand:** click a response attribute (right side of a card), then a request attribute (left side of another card). *Accept all ≥ 0.9* takes the strong suggestions in one click. |
| **APIs** | Every request field shows its type and the constraints found in the code. Each field has a rule editor: `kind` plus enum, pattern, template or const, and a preview. Response fields and the API's threads are listed too. |
| **Data** | All generated payloads of an API, as a table or as raw JSON. Linked attributes show as `⟵ api.field`. You can regenerate one API or all of them, and the file path is shown. |
| **Workflows** | Build one automatically from the accepted threads, or edit it as JSON. *Run* shows each step's status and time, the value that travelled along each thread, and the request and response. *Export k6* registers a load test. |
| **Runs** | Every run, with each request and response. |
| **AI ✦** | The assistant, with one-click tasks: *Decide threads*, *Realistic data*, *Design workflow*, *Explain failures*, *Load test it*. Write actions wait for your approval. |

## Driving it from a coding agent (MCP)

The same operations are MCP tools, so Claude Code, Cursor, Antigravity and other agents get them automatically:

| Tool | Does |
|---|---|
| `apilab_create_project` (name, repo, service, base_url, headers) | Creates the folder structure. `headers` sets default request headers such as `Authorization`. |
| `apilab_discover` (project, source auto/openapi/source/mappings) | Discovers the APIs and suggests threads. |
| `apilab_apis`, `apilab_api` | Lists APIs, or shows one in full: fields, rules, threads and a sample. |
| `apilab_links`, `apilab_link_set`, `apilab_link_status` | Reviews, creates, accepts, rejects or deletes threads. Each thread needs a reason. |
| `apilab_set_rules` (api, {field: rule}) | Sets data rules and returns a preview. Code enums are enforced. |
| `apilab_generate`, `apilab_data` | Generates and reads payloads. |
| `apilab_workflow_auto`, `apilab_workflow_save`, `apilab_workflows` | Builds, designs or reads workflows. Bindings are validated. |
| `apilab_workflow_run`, `apilab_runs` | Runs a workflow and reports per-step results and failures, with the request and response. |
| `apilab_export_k6` | Registers the workflow as a load test. Run it with `loadtest_run`. |
| `apilab_java_suite` | Runs the repository's Java generator (`scripts/loadtest.sh generate`, JDK 25) into `java-suite/`. |

The prompt **`design-test-data`** (project, repo, service) walks the whole path: discover, decide threads, set
realistic rules, generate, build the workflow, run it until it passes, then export to k6.

Example requests:
- *"Create an API Lab project for the orders repo, discover it, and decide which response fields feed which
  requests. Explain your choices."*
- *"Give every request field of orders realistic data: real ISO country codes, EUR/USD/GBP, quantities 1–10, emails
  that match the names."*
- *"Design the checkout workflow (customer → order → pay → read → cancel), run it 5 times and fix anything that fails."*
- *"Export checkout as a stress test with 50 VUs and run it, then tell me the slowest line from the JFR profile."*

## Fake-data rules (`rules/<api>.json`)
```json
{
  "body.email":       {"kind": "email"},
  "body.countryCode": {"enum": ["PT", "DE", "US"]},
  "body.sku":         {"pattern": "SKU-####-??"},
  "body.username":    {"template": "qa-user-{{seq}}"},
  "body.amount":      {"kind": "money", "min": 10, "max": 500},
  "body.note":        {"null_rate": 0.3},
  "body.orderId":     {"pool": "orderPlace.id"},
  "header.X-Tenant":  {"const": "acme"}
}
```
- **Kinds:** email, first_name, last_name, full_name, username, password, phone, company, city, country,
  iso_country, street, zip, uuid, date, datetime, past_date, future_date, time, url, word, words, sentence,
  paragraph, int, decimal, money, bool, currency, sku, code, ip, color, iban, lat, lon.
- **Without a rule**, the kind is guessed from the field's name, format and type, and the constraints from the code
  always apply: `@Size` max length, `@Min`/`@Max` ranges, enums, and simple `@Pattern` regexes.
- **Reproducible:** generation is seeded per API, so the same rules give the same data.
- **Contract-safe:** every value is made to satisfy the field's `@Pattern` (Java classes like `\p{L}` included). A new
  rule is sampled 50 times against the field's format, pattern, length and enum, and refused with an example if it
  could produce an invalid value. For dates use `{"kind": "past_date", "min": "-15y", "max": "-1m"}`; ISO dates also work.
- **Related attributes:** bind an object's id and name from the same earlier step response, not from the pool, so
  they match.

## Workflow JSON (`workflows/<id>.json`)
```json
{"id": "checkout", "think_time_ms": 300, "steps": [
  {"id": "s1", "api": "customerCreate"},
  {"id": "s2", "api": "orderPlace", "bind": {"body.customerId": "{{s1.response.id}}"}},
  {"id": "s3", "api": "orderPay",   "bind": {"path.orderId": "{{s2.response.id}}"}, "expect": {"status": [200]}}
]}
```
- **Binding forms:** `{{<step>.response.<path>}}`, `{{<step>.request.<path>}}`, `{{pool:<api>.<field>}}`, a literal,
  or a string with embedded expressions.
- **Paths:** response paths are dotted; `items[].id` takes the first element.
- **Validation:** bindings may only refer to earlier steps, and fields must exist.

## Verified on a real project: spring-petclinic-rest
- **Host it.** [spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest) was cloned into the
  workspace, then built and deployed by devctl under **process-compose v1.75.2**. That's a git worktree build with
  OpenAPI generation and Maven, deployed as a jar with automatic JFR. Health came up in about 10 s.
- **Discover it.** OpenAPI from the running service and the API-first Java source scan agree exactly: 36 APIs.
  Petclinic's real constraints were read from the generated DTOs, for example `telephone ^[0-9]*$`, names
  `^[\p{L}]+…`, and max lengths.
- **AI as the deciding mind.** A Claude Code session, over MCP only:
  - accepted the clinic threads and rejected the irrelevant ones;
  - set data rules;
  - designed the `clinic-visit` workflow: list pet types → owner → pet → visit → read owner → read pet;
  - ran it green;
  - exported and ran the k6 load test;
  - read the JFR verdict.
- **Simulation result after the fixes below.** 240/240 functional steps over 40 iterations. The load test sent 6,642
  requests at 95 rps with p95 2.6 ms and 0 errors. JFR was GREEN. An earlier run's hot spots were
  `ClinicServiceImpl.findOwnerById` (line 203) and an uncached `findAllPetTypes` (line 155).
- **Fixed in devctl because of it.**
  - process-compose exited when its only service restarted (`--keep-project`).
  - A redeploy raced process-compose's own restart.
  - Process logs were JSON envelopes instead of raw lines.
  - The API-first source scan found 1 API instead of 36.
  - A nested `type.id` was suggested as a pet id.
  - Stale threads survived re-discovery.
  - `pool:` bindings were missing in the k6 export (33% errors).
  - Date rules ignored min/max.
  - A rule producing month `00` 10% of the time was accepted.
- **Found in petclinic.** A malformed visit date returns **500** (`HttpMessageNotReadableException`) instead of 400.

## Verified
- **Java-source discovery:** run on this repository's Spring code and on a test fixture (`tests/fixtures/shop`). On the
  fixture the five 0.92-confidence suggestions were exactly the true relationships.
- **A real Claude Code session** drove the lab over MCP. It accepted those five threads and rejected ten wrong ones
  with reasons. It set the rules, built `checkout`, and ran it 18/18 against a strict stub that rejects unthreaded
  orders. That session is also why code enums are now enforced.
- **The exported k6 script** ran on real **k6 v1.3.0**: 150/150 checks passed, with every order threaded and then
  cancelled. It also ran through devctl's load-run pipeline, ending in status `passed`.
- **`apilab_java_suite`** generated the Java suite for the fixture. It found the same customers → orders seeding order.

Not covered yet (docs/open-questions.md OQ-LD-3):
- **Request bodies of other content types:** multipart and form bodies are not modelled.
- **OAuth flows:** use project `headers` with a token.
- **GraphQL.**
