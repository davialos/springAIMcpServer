# Rule-engine ecosystem — local runbook

A complete, testable local stack around the CEL rule engine (ADR-0026, LLD-18 §12): console, auth, rule-engine API, PostgreSQL,
Prometheus, Loki, Grafana. Needs Docker (compose plugin), bash, curl and python3.

```bash
scripts/rule-engine/dev.sh up      # first run creates docker/rule-engine/.env with random secrets, builds, starts, seeds, waits
scripts/rule-engine/dev.sh smoke   # health, protobuf login on the wire, role gating, Prometheus/Loki/Grafana checks
scripts/rule-engine/dev.sh dashboards   # runs every Grafana panel query through Grafana
scripts/rule-engine/dev.sh e2e     # Playwright browser suite against the React console (:8080)
scripts/rule-engine/dev.sh e2e-angular   # the same for the Angular console (:8081), including the AI assistant
scripts/rule-engine/dev.sh down | reset | ps | logs [service] | seed | urls
```

| What | URL | Login |
|------|-----|-------|
| Console (React) | http://localhost:8080 | see below |
| Console (Angular) with the AI assistant | http://localhost:8081 | the same users |
| Grafana (dashboard "Rule engine — operations") | http://localhost:3000 | admin / admin |
| Prometheus / Loki | http://localhost:9090 / http://localhost:3100 | — |
| PostgreSQL | localhost:55433 (`postgres`, password in `.env`) | — |

Seeded users (tenant "Acme Bank" unless noted; passwords `admin123` / `user123` in `.env.example`):

| User | Role | Scope |
|------|------|-------|
| `admin` | ADMIN | Acme Bank / Retail — rules, groups, logs |
| `user` | USER | Acme Bank / Retail — authors rules and groups, no logs |
| `corp.user` | USER | Acme Bank / Corporate — sees tenant-wide rules, not Retail's |
| `globex.admin` | ADMIN | Globex (tenant-wide) — sees nothing of Acme |

## What to try
1. Sign in as `admin` (the request is a Protocol Buffers `LoginRequest`; the reply carries the user, tenant and organization ids).
2. **Library / Rules / Groups:** read the loan setup; create a rule (the CEL expression is type-checked when saved), then a rule
   group from rules + policy + trigger, and run it in the **Test bench**.
3. **Logs** (admin only): evaluations with per-rule outcomes, the authoring audit trail, the chat log. A `user` gets 403 from the API.
4. In Grafana open the dashboard: decisions over time, BLOCK share, rule errors, audit trail, sign-ins, latency, JVM, container logs.

## The AI assistant (Angular console)
Open **AI assistant** in the Angular console's menu. It answers about *your* tenant's rules, rule groups and parameter library, and
checks CEL expressions; it never changes anything. The first time a tenant's user opens it, the service provisions the tenant's
workspace, six read-only tools and the agent through the library's own admin API (a second or two), then grants that user the chat.
Administrators read the conversations under **Logs & AI conversations**.

Out of the box the answers come from a **built-in offline assistant** (keyword routing and templates over the real tool path, clearly
labelled: it is not a language model), so the stack needs no key. To use Claude, put these in `docker/rule-engine/.env` and run
`dev.sh up` again:
```
ANTHROPIC_API_KEY=sk-ant-...
SPRING_AI_MODEL_CHAT=anthropic
ASSISTANT_PROVIDER=anthropic
```
Try: *list the active rules*, *explain rule ADULT*, *list the rule groups*, *which parameters are in the library?*,
*check `customer.age >= 18`*. Log in as `globex.admin` to see that another tenant's assistant knows nothing of Acme.

## Ports and variables
All published ports are bound to 127.0.0.1 and can be changed in `.env` (`RE_UI_PORT`, `RE_UI_NG_PORT`, `RE_AUTH_PORT`, `RE_RULES_PORT`,
`RE_GRAFANA_PORT`, `RE_PROMETHEUS_PORT`, `RE_LOKI_PORT`, `RE_PG_PORT`). `.env` is git-ignored; delete it (after `reset`) to regenerate secrets.

## Troubleshooting
- *Login says locked:* 5 failures in 15 minutes lock a username; wait, or
  `docker compose -p ruleengine exec postgres psql -U postgres -d dai_store -c "delete from re_auth.re_auth_login_event where outcome <> 'SUCCESS'"`.
- *Grafana panel "permission denied":* run `dev.sh seed` (re-applies the SELECT grants after migrations).
- *Loki empty right after start:* Promtail needs a few seconds to discover containers; `smoke` retries.
- *Port in use:* change the matching `RE_*_PORT`.
- *The assistant says it is not available:* the note under the title says why. "No chat model named 'anthropic'" = the three
  variables above are not all set; "not available right now" = the service log (`dev.sh logs rule-engine-service`) names the
  provisioning step that failed (it never prints tokens).

## Not for shared environments
Seeded passwords (also shown on the login page of the local build), Grafana admin/admin, Promtail's Docker-socket mount,
published actuator ports. See ADR-0026 for the production path (host IdP supplies the token claims).
