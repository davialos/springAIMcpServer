# LLD-16: k6 load-test generator

| Field | Value |
|-------|-------|
| Status | Implemented v1 |
| Owner agent | lld-chief-architect |
| Module(s) | `spring-ai-mcp-server-common-loadtest` (developer tool, not part of the starter) |
| Related features | — (tooling) |
| Related ADRs | ADR-0022, ADR-0012, ADR-0021 |

## 1. Purpose & responsibilities
Point it at a Spring Boot project; it produces a runnable Grafana k6 suite for that project's REST APIs:
- **discovers** the operations (Java sources, OpenAPI 3, `/actuator/mappings`), their parameters and request
  bodies with validation constraints, and the project's JPA entities;
- **plans the data** of every request field: semantic kind (email, firstName, price, id …), constraints, the
  database column holding real values for it, and user-supplied values;
- **collects real data**: samples the bound columns from the database, harvests values from the running API's
  collection endpoints, and checks user-supplied ids against the database;
- **generates** per-API request builders, per-DTO data providers, load modes and a runtime that chooses the data
  source per field and request;
- **runs** the suite through the k6 binary.

Not owned: running k6 itself, result storage/dashboards (k6 outputs: `--out`), and anything inside the host
at runtime (the module is never on a host's classpath).

## 2. Context
```
  project dir ──► SpringSourceScanner ─┐          ┌──► DataPlan (FieldPlan per field: kind, pool)
  OpenAPI doc ──► OpenApiReader ───────┼► Catalog ┤
  /actuator   ──► ActuatorMappings ────┘  Merger  └──► RealDataCollector ◄── DatabaseSampler (JDBC, read-only)
                                                          │               ◄── ApiHarvester (GET collections)
  user values (json/csv/--value/--interactive) ──────────┤
                                                          ▼
                                          K6SuiteGenerator ──► suite/ (k6 project) ──► K6Runner ──► k6
```

## 3. Public contracts
- CLI `scripts/loadtest.sh <discover|generate|run|modes>` (`cli.LoadTestCli`); options in §8.
- Generated suite layout (stable; documented in the suite README):

| Path | Owner | Regenerated |
|------|-------|-------------|
| `main.js` | generator | yes — options per `MODE`, `api_<id>` exec per API, `mixed`, `all`, `preview` |
| `apis/<id>.js` | generator | yes — `build(ctx)` → `{path, query, headers, body}`; stale ones removed |
| `providers/schemas.js` | generator | yes — one provider function per request DTO reachable from a request |
| `lib/*.js`, `lib/dictionaries.json` | generator | yes — runtime (data sources, dummy/random, modes, HTTP, report) |
| `loadtest.config.json` | team | merged — team values win; new APIs/profiles added; removed APIs dropped |
| `data/user.json` | team | merged — new values appended |
| `data/real.json` | generator | pools re-sampled; pools not re-sampled this time are kept |
| `data/plan.json`, `README.md`, `run.sh` | generator | yes |
| `hooks.js` | team | never (created once) |

- Field keys (one scheme for plan, JS and user data; `data.FieldKeys`): `<apiId>.path|query|header.<name>`,
  `<apiId>.body[.<prop>…]` for inline bodies, `<SchemaName>.<prop>[.<prop>…]` for DTO properties.

## 4. Data model
- `model`: `ApiCatalog(project, basePath, endpoints, schemas, entities)`, `ApiEndpoint(id, method, path,
  summary, tags, params, body, resource, sources)`, sealed `Schema` = `ScalarSchema | ArraySchema |
  ObjectSchema | RefSchema`, `Constraints(minLength, maxLength, minimum, maximum, pattern, temporal)`,
  `EntityTable(entity, schema, table, idField, idColumn, fieldColumns, fieldReferences, joinColumns,
  sensitiveFields)`.
- `data`: `FieldKind` (55 kinds), `FieldPlan(key, name, owner, kind, pool, sensitive)`, `PoolRef(schema, table,
  column)` (key `[schema.]table.column`), `UserData(fields, payloads, bindings)`.
- `data/user.json`: `{"fields": {key: [values]}, "payloads": {apiId: [bodies]}, "bindings": {key: "table.column"}}`;
  a field key may be the full key, `<apiId|Schema>.<field>` or the bare field name. CSV: header = keys.

## 5. Key flows
**Discovery.** (1) Sources: every `src/main/java` file is parsed (javac tree API, `-proc:none`, no
attribution); types are indexed by simple name; `static final String` constants are evaluated so
`@RequestMapping(ApiPaths.BASE + "/x")` resolves. (2) Controllers: `@RestController`, `@Controller`, classes
with `@RequestMapping`, and interfaces carrying mapping annotations (API-first); `{var:regex}` becomes a
`pattern` constraint; generic handler names get the controller subject (`get` → `getCustomer`).
(3) Parameters: `@PathVariable`, `@RequestParam` (required/defaultValue/Optional), `@RequestHeader`
(`Authorization` dropped — auth is configured), `@RequestBody`, `Pageable` → `page`/`size`, unannotated
simple types → optional query params, query objects → their scalar properties; framework types skipped;
multipart operations skipped with a log line. (4) DTOs → named schemas with Jakarta Validation (`@NotNull`,
`@NotBlank`, `@Size`, `@Min`/`@Max`, `@Positive…`, `@Digits`, `@Email`, `@Pattern`, `@Past`/`@Future`),
Jackson (`@JsonProperty`, `@JsonIgnore`, READ_ONLY, `@JsonNaming`, global SNAKE_CASE) and OpenAPI `@Schema`
(example, allowableValues, required). (5) Merge by `METHOD + path with anonymised variables`; filters
(Ant patterns, `METHOD pattern`, or an id) and default excludes.

**Data plan.** Kind = format → enum → name heuristics → type. Real-data binding (`RealDataBinder`), cautious:
explicit bindings first; then identifiers (`{id}` → PK of the collection before it, `customerId` → PK of
`customer(s)`), natural keys (`productSku` → `product.sku` when `sku` is Product's `@Id`), query filters of
the resource (`GET /customers?email=` → `customers.email`); never a sensitive field; other body fields stay
generated (no unique-constraint collisions on creates). Tables resolve through JPA entities and/or JDBC
metadata (`TableIndex`; when the database is known, it decides what exists).

**Real data.** Per pool: `SELECT v FROM (SELECT DISTINCT col AS v FROM t WHERE col IS NOT NULL) d ORDER BY
<random>` with `setMaxRows` and a 30 s timeout; empty pools are harvested from parameterless GET collection
endpoints of the same table (array, page wrappers `content/items/data/results/…`, HAL `_embedded`) and those
values are checked in the database when one is configured; user values of bound fields are checked
(`WHERE col IN (…)`, chunks of 500, values typed by column type) and, with `--drop-unverified`, filtered.

**Run time (k6).** `field(ctx, spec)` chooses the source by `DATA_MODE`: `auto` user > real > dummy;
`dummy`; `random` (constraint-driven, regex-generated strings; 4xx counted as expected); `real` real > user >
dummy; `user` user > dummy; `mixed` weighted per field per request (`data.mix`). Identifiers stay real in
every mode except `user`/`real` (`realIdentifiersInAllModes`). Whole user payloads replace a body in
`auto`/`user` (and at the user weight in `mixed`). Unique kinds (email, username, code, slug) get a per-VU
iteration suffix.

**Modes.** Profiles in `loadtest.config.json → modes`: `smoke` (1 VU, 3 iterations of every API), `load`,
`stress` (1×–4× base VUs in steps), `spike` (10× within 10 s), `soak` (1 h), `breakpoint` (arrival rate ramp,
abort on first failed threshold). Per-API form runs one scenario per API in turn (isolates the API that
degrades; `PER_API=parallel` overlaps them); `mixed-<profile>` runs one scenario choosing an API per iteration
by weight (GET 6, POST 2, PUT/PATCH 1, DELETE 0 by default). `preview` builds and prints requests without
sending. Scale with `VUS`, `RATE`, `DURATION_SCALE`; narrow with `API=a,b`.

## 6. Failure modes & resilience
| Failure | Detection | Behavior | Recovery |
|---|---|---|---|
| Database unreachable / bad credentials | `SQLException` on connect | logged; real data from database disabled; generation continues | fix `--db-*`, regenerate |
| Column missing / sampling error | metadata check / `SQLException` | pool skipped with a log line; fields fall back to user/dummy | `--bind` to the right column |
| Slow table | statement timeout 30 s, max rows | that pool is skipped | `--sample-size`, `--bind` a smaller column |
| Harvest endpoint fails | HTTP status / timeout 15 s | logged; pool stays empty | start the app, `--header` for auth |
| Unparseable source file | javac still yields a tree | best effort; missing types become free-form | add OpenAPI |
| Unknown `MODE`/`API` | suite init | k6 exits non-zero with the list of valid values | — |
| Production-looking target | `safety.blockedHostPattern` | suite refuses to start | `ALLOW_PROD=true` if intended |

## 7. Security
- Never runs inside a host; read-only JDBC connection; identifiers from configuration are matched against JDBC
  metadata and quoted; values are always bound parameters.
- Sensitive fields (name rule in `model.Names`, `@AiEntityProperty`/`@AiContext` CONFIDENTIAL/RESTRICTED,
  OpenAPI `format: password`) are never bound to real data unless explicitly bound — real values are copied
  into `data/real.json`, so treat suites with real data like test fixtures from that database.
- Secrets only from the environment (`AUTH_TOKEN`, `AUTH_USER`/`AUTH_PASSWORD`, `API_KEY`,
  `LOADTEST_DB_PASSWORD`); the config holds `${ENV}` placeholders only. The JDBC URL is logged with any
  `password=` parameter masked.
- Dummy e-mail/web domains are RFC 2606 reserved (`example.com/.org/.net`); card numbers are public test numbers.
- DELETE disabled by default; `READ_ONLY=true` / `safety.readOnly` restrict a run to GET/HEAD.

## 8. Configuration
CLI (generation): `--project`, `--openapi`, `--actuator`, `--include`/`--exclude`/`--no-default-excludes`,
`--header`, `--out`, `--base-url`, `--data-mode`, `--db-url`/`--db-user`/`--db-password`/`--db-schema`/`--no-db`
(defaults: the project's `spring.datasource.*`), `--sample-size` (200), `--harvest`, `--user-data`, `--value`,
`--bind`, `--interactive`, `--drop-unverified`, `--auth`/`--login-path`. Suite: `loadtest.config.json`
(`baseUrl`, `headers`, `http.timeout`, `thinkTime`, `auth`, `data.*`, `safety.*`, `thresholds`, `defaults.p95Ms`,
`defaults.maxErrorRate`, `perApi`, `modes`, `apis.<id>.{enabled, weight, expectedStatuses, p95Ms, maxErrorRate}`)
and env (`MODE`, `DATA_MODE`, `BASE_URL`, `API`, `VUS`, `RATE`, `DURATION_SCALE`, `PER_API`, `READ_ONLY`,
`ALLOW_PROD`, `PREVIEW_COUNT`).

## 9. Observability
Every request is tagged `api=<id>` and `name=<METHOD template>` (no high-cardinality URLs). Per-API thresholds
on `http_req_duration{api:…}` (p95) and `http_req_failed{api:…}` also surface per-API rows in the summary.
`handleSummary` prints a per-API table and writes `reports/<mode>-<timestamp>.{json,md}`; any k6 output
(`-- --out experimental-prometheus-rw`, InfluxDB, Grafana Cloud) works unchanged.

## 10. Performance & capacity
Generation is offline and linear in source size. At run time pools and user values are k6 `SharedArray`s
(one copy shared by all VUs); dummy/random values are generated per request (no pre-generated payload files
to exhaust); regex patterns are parsed once per VU and cached.

## 11. Limits / follow-ups
Multipart and form bodies are skipped; Kotlin sources are not scanned (use `--openapi`/`--actuator`);
polymorphic DTOs (interfaces/abstract classes) become free-form objects; request chaining (create → read the
created id) is a `hooks.js` recipe rather than generated; MySQL/SQL Server/Oracle sampling needs the driver on
`LOADTEST_CLASSPATH` (only PostgreSQL is tested).
