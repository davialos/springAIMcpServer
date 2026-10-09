# CEL faker & flow studio — user guide

From the API contracts you already have to valid CEL expressions, test data and a k6 workflow suite. Design: LLD-19, ADR-0030.

## 1. Start
```bash
scripts/celfaker.sh serve            # dashboard on http://localhost:8099 (loopback only)
scripts/celfaker.sh example > my.json # an example contract to edit
scripts/celfaker.sh generate --contract my.json [--workflow flow.json] [--value-map attribute-map.json] --out build/celfaker
k6 run -e BASE_URL=http://localhost:8080 build/celfaker/k6/main.js
```
Needs JDK 25 and (to run the suite) [k6](https://k6.io). Node is only needed for the UI tests (`scripts/celfaker-ui-test.sh`).

## 1b. On a Mac with the local-dev control plane
`local-dev/bin/devctl dashboard` shows the studio as a **Faker** tab and runs it as the process-compose service `celfaker` (port 8110, JDK 25, self-compiling on first start); your other
local services appear as one-click “Running locally” import sources. See `local-dev/README.md`.

## 1a. Add APIs the easy way (dashboard, tab 1)
- **Swagger / OpenAPI URL** — type the service address (`http://localhost:8080`) or its docs URL; the dashboard finds `/v3/api-docs`, `/swagger.json`, … reads every operation, builds example bodies from the schemas,
  turns schema constraints into CEL rules and proposes which GET operations are validation APIs. Tick the ones to add.
- **Paste or open a spec** — Swagger 2 / OpenAPI 3, JSON or YAML.
- **cURL command** — paste from a terminal, browser DevTools (“Copy as cURL”) or Postman (“Code → cURL”). Tokens and cookies are replaced by `{{env.NAME}}`.
- Then, per API: **Generate fake input** (valid, rule-satisfying and invalid bodies; download, or use the first as the request example) and **Send** (calls the real service; put `TOKEN=…` in the environment box).

## 2. The contract (`ApiContract`)
```jsonc
{ "name": "shop", "baseUrl": "http://localhost:8080",
  "apis": [
    { "id": "createCustomer", "name": "Create customer", "method": "POST", "path": "/api/customers", "role": "ACTION",
      "sysObject": "customer",                       // CEL name = customer.<field>
      "description": "documentation shown in the dashboard and the generated README",
      "headers": { "Authorization": "Bearer {{env.TOKEN}}" },
      "requestExample": { "email": "ada@example.com", "age": 34 },
      "responseExample": { "id": 1001 },             // proposes variables for the next step
      "selectedPaths": [],                           // body paths to use as parameters (empty = all)
      "mapPaths": [],                                // body paths to treat as one MAP parameter
      "rules": ["customer.age >= 18 && customer.age <= 120"],   // CEL; valid data satisfies, negative data violates
      "expectedStatus": [201], "invalidStatus": [400, 422] },
    { "id": "validateOrder", "method": "GET", "path": "/api/orders/{id}/validate", "role": "VALIDATION",
      "validates": "createOrder", "expectedStatus": [200], "expectBody": { "valid": true } } ] }
```

## 3. What you get
| File | Content |
|------|---------|
| `parameters.json` | `name`, `sysObject`, `sysObjectAttribute`, `dataType`, `sample`, `boundTo` (API:path) |
| `attribute-map.json` | per parameter `valid` / `boundary` / `invalid` values — **edit and pass back with `--value-map`** |
| `expressions.json` | every faked CEL expression with category and meaning (+ `rejected`) |
| `cel-cases.json` | per expression: input combinations and the result CEL computed (`true` / `false` / `error`) |
| `k6/` | `main.js`, `lib/runtime.js`, `workflow.json`, `apis.json`, `data/<api>.valid.json`, `data/<api>.invalid.json`, `README.md` |
| `summary.json` | counts and warnings |

## 4. Workflows
Draw them in the dashboard (**Flow designer**) or write `workflow.json`:
```json
{ "name": "shop-flow", "load": { "profile": "load", "vus": 10, "duration": "1m", "negatives": 100 },
  "steps": [
    { "id": "customer", "api": "createCustomer", "extract": [{ "name": "id", "from": "body.id" }] },
    { "id": "order", "api": "createOrder", "dependsOn": ["customer"],
      "inject": [{ "target": "body.customerId", "value": "{{customer.id}}" }],
      "extract": [{ "name": "id", "from": "body.id" }] },
    { "id": "check", "api": "validateOrder", "dependsOn": ["order"],
      "inject": [{ "target": "path.id", "value": "{{order.id}}" }] } ] }
```
Profiles: `smoke` (one iteration), `load`, `stress`, `spike`, `custom` (constant VUs). The `negative` scenario sends each invalid body after running
its prerequisite steps and expects a rejection status. Secrets: `k6 run -e TOKEN=… main.js` and `{{env.TOKEN}}`.

## 5. Tips
- Teach the faker your domain: put real ids and business values into `attribute-map.json` `valid`; add rules to APIs so valid data satisfies them.
- A negative case the API legitimately accepts (an optional field) fails its check: remove it from `data/*.invalid.json` or change `invalidStatus`.
- Rules use CEL with the standard macros (`has`, `all`, `exists`, `exists_one`, `map`, `filter`); the "inputs → results" dialog shows exactly what the rule engine will answer.
