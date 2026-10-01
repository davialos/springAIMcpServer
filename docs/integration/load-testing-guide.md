# Load testing a Spring Boot project with k6

How to point `spring-ai-mcp-server-common-loadtest` at a project and run load tests. Design: LLD-16, ADR-0022.

Requirements: JDK 25 (generator), [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) ≥ 0.50 (runner).
The generated suite only needs k6.

## 1. Discover

```
scripts/loadtest.sh discover --project ../shop --json
```

Lists every operation found and, with `--json`, every request field with its kind and real-data binding.
Add `--openapi http://localhost:8080/v3/api-docs` when the project publishes OpenAPI (it becomes the
authoritative contract), and `--actuator http://localhost:8080/actuator/mappings` to include routes that only
exist at run time (e.g. this library's dynamic endpoints). Narrow with `--include '/api/**'`, drop with
`--exclude 'DELETE /**'` or an API id.

## 1b. Or start from a browser recording

Record the flow you want to load-test in Chrome/Edge: DevTools ▸ **Network** (tick *Preserve log*), click
through the app, then **Export HAR** (the download icon; the default *sanitized* export is enough).

```
scripts/loadtest.sh discover --har shop-checkout.har                    # what was recorded
scripts/loadtest.sh generate --har shop-checkout.har --out shop-load    # suite from the recording alone
scripts/loadtest.sh generate --project ../shop --har shop-checkout.har  # recording + sources (best)
```

You get every API the page called (with URL templates such as `/orders/{orderId}`), the values that were
sent (as user data, so `auto` mode reuses them), and `data/journey.json`: the calls in order, with their
pauses, where ids returned by one call (the order just created) feed the next ones. Replay it under any
profile: `./run.sh journey-preview`, `./run.sh journey-smoke`, `./run.sh journey-spike`, `./run.sh
journey-stress dummy` (same flow, generated values). Calls to other hosts (analytics, CDNs) are ignored unless
you pass `--har-host`. Cookies, tokens, CSRF headers and password fields are never kept; pass
`--har-no-values` to keep the APIs and the flow but none of the recorded values.

## 2. Generate

```
scripts/loadtest.sh generate --project ../shop \
    --openapi http://localhost:8080/v3/api-docs \
    --harvest \
    --user-data qa-values.json --value 'CreateOrderRequest.couponCode=SPR-2026' \
    --drop-unverified
```

- **Database (real data):** read from the project's `spring.datasource.*` (placeholders resolve from the
  environment) or `--db-url/--db-user/--db-password`. Identifier, foreign-key and filter fields get pools of real
  values (`data/real.json`); user-supplied ids are checked against the database (`--drop-unverified` removes
  missing ones). The connection is read-only; sensitive columns are never sampled.
- **API (real data):** `--harvest` fills empty pools from the running app's collection endpoints.
- **Your data:** `--user-data` (JSON `{fields, payloads, bindings}` or CSV with field keys as header), `--value
  key=v1,v2`, or `--interactive` to be asked API by API. Field keys are listed in the suite README.
- **Bindings:** `--bind 'createOrder.body.customerId=customers.id'` forces a field onto a column.

Output (default `<project>/load-tests/`): `main.js`, `apis/<id>.js` (request builder per API),
`providers/schemas.js` (data provider per request DTO), `lib/` runtime, `loadtest.config.json`, `data/`,
`hooks.js`, `README.md`, `run.sh`. Commit it next to the project; regenerate after API changes — your config,
user data and hooks are kept.

## 3. Run

```
cd ../shop/load-tests
./run.sh preview                      # see the generated requests and where each value came from
./run.sh smoke                        # every API, a few times — must pass before anything heavier
./run.sh mixed-load                   # production-like weighted mix
./run.sh mixed-spike mixed            # sudden 10x surge; data from every source
API=createOrder ./run.sh stress dummy # find where one API degrades
./run.sh mixed-stress                 # find where the system degrades under the mix
./run.sh breakpoint                   # ramp arrival rate until a threshold fails
```

| Load mode | Shape |
|---|---|
| `smoke` | 1 VU, 3 iterations over all APIs |
| `load` / `mixed-load` | ramp to base VUs, hold 5 min |
| `stress` / `mixed-stress` | 1x, 2x, 3x, 4x base VUs, 3 min each |
| `spike` / `mixed-spike` | baseline, 10x within 10 s, hold, recover |
| `soak` / `mixed-soak` | base VUs for 1 h |
| `breakpoint` / `mixed-breakpoint` | arrival rate ramps to 20x; aborts at the first failed threshold |

Every profile also has a `journey-<profile>` form that replays the recorded browser flow (section 1b).

| Data mode (`DATA_MODE`) | Values |
|---|---|
| `auto` | user > real > dummy |
| `dummy` | realistic generated values (ids stay real) |
| `random` | random within constraints, 4xx accepted |
| `real` | sampled / harvested values |
| `user` | your values |
| `mixed` | weighted per field and request (`data.mix`) |

Per-API modes run one API at a time; `mixed-*` modes pick an API per iteration by `apis.<id>.weight`.
Scale without editing: `VUS=50 RATE=100 DURATION_SCALE=0.25`. Target another environment with `BASE_URL`.
Auth: set `auth.type` in `loadtest.config.json` (`bearer` → `AUTH_TOKEN`, `basic` → `AUTH_USER`/
`AUTH_PASSWORD`, `apiKey` → `API_KEY`, `login` → posts `auth.login` once in `setup()`).

Results: a per-API table on stdout and `reports/<mode>-<timestamp>.{json,md}`. Stream to Grafana with any k6
output, e.g. `./run.sh mixed-load auto --out experimental-prometheus-rw`.

## 4. Safety

DELETE operations are disabled until you enable them per API; `READ_ONLY=true` restricts a run to GET/HEAD;
hosts that look like production (`safety.blockedHostPattern`) are refused unless `ALLOW_PROD=true`. Never point
a write-enabled run at shared data you cannot restore.
