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
- **discovers** the operations (Java sources, OpenAPI 3, `/actuator/mappings`, browser recordings in HAR
  format), their parameters and request bodies with validation constraints, and the project's JPA entities;
  for Java/Spring projects every endpoint style: annotated controllers (incl. composed stereotypes and generic
  base controllers), `@HttpExchange` interfaces a controller implements, WebMvc.fn `RouterFunction` routes,
  Spring Data REST repositories, and API-first projects (bundled OpenAPI + generated interfaces);
- **builds the table relationship graph** from JPA mappings, the database's foreign keys, or — with neither —
  the project's own DDL scripts (Flyway, Liquibase SQL, `schema.sql`), and derives payloads from it: entity
  bodies without server-managed fields, related entities sent as references, column lengths and unique columns
  respected;
- **seeds test data through the application's create endpoints**, parents before children, and feeds the
  created ids and natural keys into the load (§5 *Seeding*);
- **learns from a browser recording** (Chrome/Edge DevTools ▸ Network ▸ Export HAR): the API calls a person
  actually made, the values they sent, and the order — replayable as a correlated journey;
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
  project dir ──► SpringSourceScanner ─┐          ┌──► DataPlan (FieldPlan per field: kind, pool, facts)
   (controllers, FunctionalRouteScanner,         │          │        ▲ TableIndex (JPA entities + tables)
    DataRestScanner, bundled OpenAPI) │          │        │   tables: DatabaseSampler metadata (live DB)
  OpenAPI doc ──► OpenApiReader ───────┼► Catalog ┤        │        or SqlSchemaReader (DDL scripts)
  HAR file    ──► HarReader ───────────┤  Merger  ├──► SeedPlan (create endpoints in relationship order)
  /actuator   ──► ActuatorMappings ────┘          └──► RealDataCollector ◄── DatabaseSampler (JDBC, read-only)
                                                          │               ◄── ApiHarvester (GET collections)
  user values (json/csv/--value/--interactive) ──────────┤
  HAR observations ──► RecordedTraffic (recorded values, journey) ──┤
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
| `data/journey.json` | generator | replaced when generated with `--har`, otherwise kept |
| `data/seed.json` | generator | yes — seeding steps (`SeedPlan.toJson()`) |
| `hooks.js` | team | never (created once) |

- Field keys (one scheme for plan, JS and user data; `data.FieldKeys`): `<apiId>.path|query|header.<name>`,
  `<apiId>.body[.<prop>…]` for inline bodies, `<SchemaName>.<prop>[.<prop>…]` for DTO properties.

## 4. Data model
- `model`: `ApiCatalog(project, basePath, endpoints, schemas, entities)`, `ApiEndpoint(id, method, path,
  summary, tags, params, body, resource, sources)`, sealed `Schema` = `ScalarSchema | ArraySchema |
  ObjectSchema | RefSchema`, `Constraints(minLength, maxLength, minimum, maximum, pattern, temporal)`,
  `EntityTable(entity, schema, table, idField, idColumn, fieldColumns, fieldReferences, joinColumns,
  sensitiveFields, columnLengths, uniqueFields, idGenerated)`.
- `data`: `FieldKind` (55 kinds), `FieldPlan(key, name, owner, kind, pool, sensitive, maxLength, unique)`,
  `PoolRef(schema, table, column)` (key `[schema.]table.column`), `UserData(fields, payloads, bindings)`,
  `DbTable(schema, name, columns, primaryKey, foreignKeys, columnSizes, uniqueColumns)` (from JDBC metadata or
  DDL), `SeedPlan(steps)` with `Step(api, table, pool, idField, idFromRequest, dependsOn, deleteApi, captures,
  deletePool)`.
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

**Spring endpoint styles.** Controllers are recognised through composed stereotypes (an annotation meta-annotated
with `@RestController`/`@Controller`, whose own `@RequestMapping` contributes a base path; a class's direct
mapping wins). Handler methods are collected through `extends`/`implements` with type-variable binding, so
`class CompanyController extends AbstractCrudController<Company, Long>` yields `POST /companies` with a
`Company` body and a `Long` id; abstract bases are not endpoints themselves. `@HttpExchange`/`@GetExchange…`
interfaces count only when a controller implements them (alone they are HTTP clients). `FunctionalRouteScanner`
reads methods returning `RouterFunction` (`route().GET(…)`, `.path(prefix, b -> …)`, `nest(path(…), …)`) —
handlers are opaque, so write bodies are free-form. `DataRestScanner` (only when the build declares Spring Data
REST) turns every exported repository into list/create/get/put/patch/delete under
`spring.data.rest.base-path` with the default (uncapitalised plural) or `@RepositoryRestResource(path)` path;
bodies are `<Entity>Resource` schemas where associations are URI links. `${placeholder:default}` in mappings
resolve against the project's configuration. `server.servlet.context-path` becomes the base path (a bundled
OpenAPI server URL is rebased onto it). With `spring.jackson.deserialization.unwrap-root-value`, bodies are
wrapped as `{"<@JsonRootName or class>": …}`. API-first projects: OpenAPI documents bundled under
`src/main/resources` or a top-level `api/`/`openapi/`/`spec/`/`contracts/` directory are read automatically
(`--no-bundled-openapi` to skip), and interfaces generated into `target/generated-sources`/`build/generated`
are scanned.

**Relationships and payloads.** A JPA entity used as a request body becomes a schema without the server-managed
fields (`@GeneratedValue` id, `@Version`, `@CreatedDate`/`@LastModifiedDate`/`@CreationTimestamp`…,
`@OneToMany`/`@ManyToMany` collections, `@JsonBackReference`); a `@ManyToOne`/`@OneToOne` field becomes a
`<Target>Ref {id}` reference; `@Column(nullable = false)`/`optional = false` make a property required;
`@Column(length)` (default 255 for strings; none for `@Lob`/`columnDefinition`) becomes `maxLength`,
`precision`/`scale` a maximum. `TableIndex.reference` follows the graph: `ownerId` in `CreateDealRequest` →
`Deal.owner` is a `@ManyToOne AppUser` → `users.id`, even though no table is called `owner`; without JPA the
foreign key of the matching column decides (`author_id → users.id`). `TableIndex.facts` carries column length
(stricter of JPA and database) and uniqueness into each `FieldPlan`; the runtime truncates to `maxLength` and
suffixes unique values per VU iteration. Tables come from the live database's metadata (authoritative: an
entity it lacks is ignored) or, without a reachable database, from `SqlSchemaReader`: the project's Flyway
migrations (version order; undo scripts skipped), Liquibase formatted-SQL changelogs and `schema*.sql` are
replayed — `CREATE TABLE` (inline/table-level PK, `REFERENCES`/`FOREIGN KEY`, `UNIQUE`, `varchar(n)`),
`ALTER TABLE ADD/DROP/RENAME/ALTER … TYPE/MODIFY/CHANGE`, `CREATE UNIQUE INDEX`, `DROP TABLE`, across
PostgreSQL/MySQL/SQL Server/H2 syntax; where the DDL declares no constraint, `x_id` is inferred to reference
the single-column PK of table `x`/`xs`. DDL tables only add facts (non-authoritative index): an entity missing
from them is still used.

**Browser recordings (HAR).** `HarReader` keeps API calls only (`_resourceType` fetch/xhr, or JSON
request/response for exporters without types), drops documents/scripts/styles/images/fonts, CORS preflights,
non-HTTP schemes and non-JSON bodies, and keeps the most-called host (`--har-host` to choose). URLs become
templates: a known template from sources/OpenAPI first, then id-like segments (numbers, UUIDs, ≥16-hex, opaque
ids), then digit-bearing segments that differ between otherwise identical calls; variables are named after the
preceding collection (`/orders/9001` → `/orders/{orderId}`). Parameter and body schemas are inferred from the
values sent (`SchemaInference`: types and formats only). Precedence in the merge: OpenAPI > sources > HAR >
actuator. `RecordedTraffic` then maps every successful call (status < 400) onto the merged catalog: recorded
values of planned, non-sensitive fields become user data under the plan's keys; whole bodies without sensitive
fields become payloads; the sequence becomes `data/journey.json` — per step the API, recorded path/query/custom
headers/body, `fill` (sensitive body paths removed, generated at replay), the pause before it, and
**correlations**: an id-like value (key `id`, `…Id`, `…uuid`, `sku`, `…number`, `…code`, `slug`, `ref`) sent by
a step that an earlier response returned becomes `{"$from": step, "at": "content.0.id", "alt": [...],
"recorded": v}` — the latest response first, up to three earlier ones as fallbacks, the recorded value last.

**Data plan.** Kind = format → enum → name heuristics → type. Real-data binding (`RealDataBinder`), cautious:
explicit bindings first; then identifiers (`{id}` → PK of the collection before it, a reference field → the
relationship it maps (above), `customerId` → PK of `customer(s)`), natural keys (`productSku` → `product.sku`
when `sku` is Product's `@Id`), other path parameters (`/articles/{slug}` → `articles.slug`; with no such
table, the one table where the column is unique: `/profiles/{username}` → `users.username`), query filters of
the resource (`GET /customers?email=` → `customers.email`); never a sensitive field; other body fields stay
generated (no unique-constraint collisions on creates). Tables resolve through JPA entities and/or JDBC
metadata (`TableIndex`; when the database is known, it decides what exists).

**Real data.** Per pool: `SELECT v FROM (SELECT DISTINCT col AS v FROM t WHERE col IS NOT NULL) d ORDER BY
<random>` with `setMaxRows` and a 30 s timeout; empty pools are harvested from parameterless GET collection
endpoints of the same table (array, page wrappers `content/items/data/results/…`, HAL `_embedded`) and those
values are checked in the database when one is configured; user values of bound fields are checked
(`WHERE col IN (…)`, chunks of 500, values typed by column type) and, with `--drop-unverified`, filtered.

**Seeding.** `SeedPlan` maps every `POST` with a body to the table it creates rows in (its resource entity, the
collection segment, or the body DTO's entity; the plainest endpoint per table wins: fewest path parameters,
not Data REST) and orders the tables parents first (Kahn; a cycle is broken with a log line). A table depends
on the tables its request fields reference *and* on its own relationships (JPA references, foreign keys):
the author of an article usually comes from the logged-in user, not the payload, yet must exist first. Each
step records where the new row's id comes from (the response body — searched in wrappers such as
`{"data": {"id"}}` —, the `Location` header, or the request for client-assigned keys), the other columns
requests address rows by (`captures`: `articles.slug ← slug`, read from the response, else the request), and a
single-parameter `DELETE` addressing the row by id or a captured key. In k6 `setup()`, `seed()` creates
`seed.perTable` rows per table (`SEED_PER_TABLE`), sending every optional field so the rows are complete, with
each child's references drawn from the parents just created; the result (pool → values) is handed to every VU,
whose real-data fields prefer it over sampled values, followed by ids that the VU's own creates returned. With
`seed.cleanup`/`SEED_CLEANUP=true`, `teardown()` deletes seeded rows children first (404 counts as gone); when a
table's rows cannot all be deleted, its parents are kept (they are still referenced). Seeding is on by default,
off with `SEED=false`, `READ_ONLY=true` or `safety.readOnly`; requests are tagged `seed_<api>`/`cleanup_<api>`.
Writes go through the application (validation, events, auditing apply), never straight into the database.

**Run time (k6).** `field(ctx, spec)` chooses the source by `DATA_MODE`: `auto` user > real > dummy;
`dummy`; `random` (constraint-driven, regex-generated strings; 4xx counted as expected); `real` real > user >
dummy; `user` user > dummy; `mixed` weighted per field per request (`data.mix`). Identifiers stay real in
every mode except `user`/`real` (`realIdentifiersInAllModes`). Whole user payloads replace a body in
`auto`/`user` (and at the user weight in `mixed`). Unique kinds (email, username, code, slug) get a per-VU
iteration suffix.

**Modes.** Profiles in `loadtest.config.json → modes`: `smoke` (1 VU, 3 iterations of every API), `load`,
`stress` (1×–4× base VUs in steps), `spike` (10× within 10 s), `soak` (1 h), `breakpoint` (arrival rate ramp,
abort on first failed threshold). Journey form `journey-<profile>` runs one scenario whose iteration replays
the whole recording: in `auto`/`user` data mode with the recorded values (sensitive fields generated), in other
data modes with generated values; pauses are scaled/capped by `journey.pauseScale`/`maxPauseMs`; correlations
apply in every data mode; steps of disabled APIs (DELETE by default) are skipped; `journey-preview` prints the
steps. Per-API form runs one scenario per API in turn (isolates the API that
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
| Seed create refused | status not 2xx | `seed: <table> n/N (k failed)`; that table has fewer/no seeded rows, fields fall back to sampled/dummy data | fix the payload in `hooks.js`/`user.json`, or `apis.<id>.enabled=false` |
| Relationship cycle | Kahn finds no ready table | logged; the cycle is seeded with whatever parent ids exist | `--bind`, or seed one side via `user.json` |
| DDL statement not understood | regex miss | skipped silently (tolerant reader) | give `--db-url` for real metadata |

## 7. Security
- Never runs inside a host; read-only JDBC connection; identifiers from configuration are matched against JDBC
  metadata and quoted; values are always bound parameters.
- Sensitive fields (name rule in `model.Names`, `@AiEntityProperty`/`@AiContext` CONFIDENTIAL/RESTRICTED,
  OpenAPI `format: password`) are never bound to real data unless explicitly bound — real values are copied
  into `data/real.json`, so treat suites with real data like test fixtures from that database.
- Secrets only from the environment (`AUTH_TOKEN`, `AUTH_USER`/`AUTH_PASSWORD`, `API_KEY`,
  `LOADTEST_DB_PASSWORD`); the config holds `${ENV}` placeholders only. The JDBC URL is logged with any
  `password=` parameter masked.
- HAR files carry credentials and personal data. Cookies, `Authorization`, CSRF/XSRF, API-key, session and
  tracing headers are never read; only custom `X-…` headers are kept; sensitive body fields (same name rule) are
  never kept as values, payloads or journey literals — the replay generates them. Other recorded values (e-mails,
  names) are kept in `data/user.json`/`journey.json` on purpose; use `--har-no-values` (or record with test
  accounts) when that is not acceptable. Prefer Chrome's default "Export HAR (sanitized)".
- Dummy e-mail/web domains are RFC 2606 reserved (`example.com/.org/.net`); card numbers are public test numbers.
- DELETE disabled by default; `READ_ONLY=true` / `safety.readOnly` restrict a run to GET/HEAD.

## 8. Configuration
CLI (generation): `--project`, `--openapi`, `--actuator`, `--include`/`--exclude`/`--no-default-excludes`,
`--header`, `--out`, `--base-url`, `--data-mode`, `--db-url`/`--db-user`/`--db-password`/`--db-schema`/`--no-db`
(defaults: the project's `spring.datasource.*`), `--sample-size` (200), `--harvest`, `--user-data`, `--value`,
`--bind`, `--interactive`, `--drop-unverified`, `--auth`/`--login-path`, `--har` (repeatable), `--har-host`,
`--har-no-values` (with a HAR and no `--base-url`, the target defaults to the recorded origin + context path),
`--no-bundled-openapi`. Launcher: `LOADTEST_CLASSPATH` (extra jars), JDBC drivers for MySQL, MariaDB, SQL Server,
Oracle, H2, SQLite and DB2 found in `~/.m2` (`MAVEN_REPO_LOCAL`) or the Gradle cache (`GRADLE_USER_HOME`)
— `LOADTEST_DRIVER_SEARCH=0` turns that off. Suite: `loadtest.config.json`
(`baseUrl`, `headers`, `http.timeout`, `thinkTime`, `auth`, `data.*`, `safety.*`, `seed.{enabled, perTable, cleanup}`, `thresholds`, `defaults.p95Ms`,
`defaults.maxErrorRate`, `perApi`, `journey.{pauseScale, maxPauseMs}`, `modes`, `apis.<id>.{enabled, weight, expectedStatuses, p95Ms, maxErrorRate}`)
and env (`MODE`, `DATA_MODE`, `BASE_URL`, `API`, `VUS`, `RATE`, `DURATION_SCALE`, `PER_API`, `READ_ONLY`,
`ALLOW_PROD`, `PREVIEW_COUNT`, `SEED`, `SEED_PER_TABLE`, `SEED_CLEANUP`).

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
Multipart and form bodies are skipped (also in recordings); recorded GraphQL calls become a single
`POST /graphql` operation (no per-query split); correlation does not cover values returned in response
headers (e.g. `Location`) or tokens reused in `Authorization` (configure `auth.type=login` instead); Kotlin sources are not scanned (use `--openapi`/`--actuator`);
polymorphic DTOs (interfaces/abstract classes) become free-form objects; create → use chaining is generated
for seeding and for each VU's own creates, but a multi-step business flow (cart → checkout) is the HAR journey
or a `hooks.js` recipe; non-PostgreSQL sampling relies on the driver found in the local Maven/Gradle cache or on
`LOADTEST_CLASSPATH` (only PostgreSQL is tested); Liquibase XML/YAML/JSON changelogs are not read (SQL
changelogs are) — give `--db-url` or JPA entities; functional-route handlers and Data REST bodies cannot be
typed beyond the entity; composite foreign keys are not followed.
