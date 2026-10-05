---
name: loadtest-generate
description: Create or refresh a Grafana k6 load-test suite for a Spring Boot project and get its smoke test passing. Use when someone wants to load test, performance test or stress test a Spring/Java REST API, asks for k6 scripts for their endpoints, or wants test data generated from their entities/tables. Discovers every endpoint (controllers, functional routes, Spring Data REST, OpenAPI), builds payloads from the entity relationships, seeds data parents-first, and iterates until `smoke` passes.
---

# Generate a load-test suite for a Spring project

Goal: a suite in `<project>/load-tests/` whose `smoke` run passes against a test environment, with every
change you made explained. Smoke passing is the gate for any heavier mode.

## Tools

Prefer the `spring-loadtest` MCP tools: `loadtest_discover`, `loadtest_generate`, `loadtest_schema`,
`loadtest_run`, `loadtest_report`, `loadtest_compare`, `loadtest_modes`. Paths are relative to the directory the server was
started in. Without the MCP server, use the same steps through one of:

| Build | Discover / generate / run |
|---|---|
| any | `$LOADTEST_HOME/scripts/loadtest.sh discover\|generate --project <dir>`, `… run --suite <dir>/load-tests --mode smoke` |
| Maven (on JDK 25) | `mvn loadtest:discover`, `mvn loadtest:generate`, `mvn loadtest:run -Dloadtest.mode=smoke` |
| Gradle | `loadtest init-gradle --project .` once, then `./gradlew loadtestGenerate loadtestRun` |

Requirements: JDK 25 for the generator, k6 ≥ 0.50 on the PATH (or `K6_BIN`) for runs.

## Steps

1. **Discover** (`loadtest_discover`, with `fields: true` when debugging data). Check:
   - The API count matches the project. Endpoints missing? Look for Kotlin controllers, WebFlux, or routes only
     registered at run time; add the app's OpenAPI document (`/v3/api-docs`) or `/actuator/mappings` with the CLI
     (`--openapi`, `--actuator`).
   - `seedOrder`: parents before children (`companies → contacts → deals`). A create endpoint that should seed but
     is missing usually means its body type has no entity/table behind it; that is fine when the item route
     (`/x/{id}`) exists, since REST resources are seeded too.
   - Exclude what must never be load tested (`exclude: ["/admin/**", "POST /payments/**"]`); ask the user when unsure.
2. **Generate** (`loadtest_generate`). Leave `database: false` unless the user agrees to the generator reading
   their database (it samples real values from `spring.datasource.*`, read-only, never sensitive columns).
   To see what the database really looks like first (tables, keys, indexes, views as they are *now*, not as the
   migrations intended), call `loadtest_schema`: it returns the structure as DDL, never row data, from the same
   `spring.datasource.*` and with the same user agreement. With `database: true`, generation also writes it to the
   suite's `data/schema.sql`.
   Supply values only the user knows with `values` (`{"CreateOrderRequest.couponCode": ["SPR-2026"]}`).
3. **Confirm the target** with the user before any run: the base URL (from `application.yml` unless overridden),
   that it is not production, and that seeding may create rows there (`SEED=false` turns it off; `SEED_CLEANUP=true`
   deletes seeded rows afterwards). Check the app answers (`curl -s <baseUrl>/actuator/health` or any GET).
4. **Preview, then smoke**: `loadtest_run` with `mode: "preview"` shows the requests without sending anything;
   then `mode: "smoke"` (1 VU, 3 iterations of every API).
5. **Fix failures at their cause** (table below), regenerate if you changed generator inputs, rerun smoke.
   Repeat until it passes. Never loosen thresholds, never disable an API to get green without the user's
   agreement, and never edit generated files (`apis/`, `providers/`, `lib/`, `main.js`): they are overwritten.
   The team-owned files are `loadtest.config.json`, `data/user.json` and `hooks.js`.
6. **Report**: APIs covered, seeding order, what you changed and why, the smoke numbers, and the next step
   (`mixed-load`, or the `loadtest-capacity` / `loadtest-analyze` skills).

## Triage

| Symptom in the smoke output | Likely cause | Fix |
|---|---|---|
| `seed: deals 0/5 (5 failed)` | the create needs a value the generator cannot guess (enum, code, existing reference) | add it to `data/user.json → fields` (or `values` on generate); check the app log for the validation message |
| 400/422 on a create | generated value breaks a rule not visible in the code (custom validator, DB check) | user value for that field, or a `beforeRequest` tweak in `hooks.js` |
| 401/403 everywhere | auth not configured | `loadtest.config.json → auth` (`bearer` + `AUTH_TOKEN`, `basic`, `apiKey`, `login` with `auth.login.path/body/tokenPath`) |
| 404 on `/x/{id}` | ids that do not exist | seeding off or failing for `x`; `data/seed.json` lacks the table → check discover's `seedOrder`; bind with `--bind 'getX.path.id=x.id'` |
| 409 on creates | unique column collides | the field should be marked unique (JPA `@Column(unique = true)` or a DB unique index); give user values |
| 5xx | an application bug or missing dependency | do not mask it: report endpoint, payload and the app's stack trace to the user |
| `Thresholds FAILED` with only p95 | slow endpoint, not broken | report it; the user decides between fixing the code and adjusting `apis.<id>.p95Ms` |
| k6 refuses to start: production host | `safety.blockedHostPattern` matched | confirm with the user; only they may set `ALLOW_PROD=true` |
