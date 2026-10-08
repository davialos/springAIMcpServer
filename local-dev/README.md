# local-dev — local development control plane (macOS)

One place to **build any repo@branch**, **deploy** it (Spring Boot jars *and* multi-WAR apps on JBoss EAP), watch
**logs and health**, and run **Grafana / Loki / Prometheus / Postgres** — from a **TUI** (process-compose), a
**web dashboard**, a **CLI**, or an **MCP server** for Claude / Cursor / other agents. Standalone: Python 3.9+ stdlib
only, not part of the Maven reactor (ADR-0029).

```
 repos folder ──discover──▶ dashboard dropdowns (repo → branch → Build)
   git worktree per repo@branch ─build─▶ ~/localdev/runs/<id>/{build.log,artifacts/}
   Deploy ─▶ jar: ~/localdev/deployed/<svc>/app.jar   war: ~/localdev/jboss/standalone/deployments/<ctx>.war
   devctl generates ~/.localdev/process-compose.yaml ─▶ process-compose (headless, REST port 8099) ◀── TUI attach
   CLI / dashboard / MCP all use the same functions + the same files (no hidden state)
```

## Set up (macOS)

```bash
brew bundle --file local-dev/Brewfile          # process-compose, git, maven, JDKs, k6, OrbStack
local-dev/bin/devctl init                      # writes ~/.localdev/config.json (edit it, see below)
local-dev/bin/devctl doctor
local-dev/bin/devctl dashboard                 # http://127.0.0.1:8765
```
Put `local-dev/bin` on your PATH (or alias `devctl`).

## Config (`~/.localdev/config.json`, example: `config.example.json`)

| Key | Meaning |
|---|---|
| `workspace` | folder whose sub-folders are git repos — everything in it appears in the dropdown |
| `builds_dir` | **build placement folder**: `runs/` (logs+artifacts), `work/` (worktrees), `deployed/`, `jboss/` |
| `java_home` | path or `auto:21` (uses `/usr/libexec/java_home -v 21`); override per repo/service/jboss |
| `repos.<name>` | `build_cmd`, `artifact_globs`, `java_home`, `env`, `in_place` (build in the real checkout; needs clean tree). Defaults are auto-detected for Maven / Gradle / npm |
| `services.<name>` | `kind`: `jar` (own process, `SERVER_PORT`), `war` (hot-deployed into JBoss), `command` (any process: `cmd`, `cwd`); `repo`, `artifact` (glob over build artifacts), `port`, `health` (`{url}` or `{port}`), `env`, `depends_on`, `context` (war) |
| `stacks.<name>` | services started/stopped together (e.g. several WARs that must run side by side) |
| `jboss` | `home` (EAP install), `port_offset`, `java_opts`, `always_on` |
| `loadtests.<name>` | k6 run: `script` (relative to `loadtest_dir`), `vus`, `duration`, `env` → process `k6-<name>`, started on demand |
| `infra.ship_logs` / `loki_url` | ship all process, JBoss and build logs to Loki (default on when `loki` is enabled) |
| `infra.enabled` | any of `prometheus`, `loki`, `grafana`, `postgres` (docker containers; Prometheus auto-scrapes every jar service on `/actuator/prometheus`; Grafana comes with Prometheus + Loki datasources) |

### Multiple WARs on JBoss EAP
All `kind: war` services share **one** JBoss EAP process (`jboss-eap` in process-compose) using a private base dir
(`~/localdev/jboss/standalone`, copied once from `$JBOSS_HOME/standalone`, so the install is never modified).
Deploying a WAR copies it to `deployments/<context>.war` (hot deploy); undeploy removes it. Readiness is
`http://127.0.0.1:9990/health/ready` (+ `port_offset`). Each WAR gets its own health URL
(default `http://localhost:8080/<context>/`). Different Java versions per service: set `java_home` on `jboss`.

## Load tests (k6)
Define runs under `loadtests` and put the scripts in `loadtest_dir` (the repo's own generator can produce them:
`scripts/loadtest.sh`, docs/integration/load-testing-guide.md). `devctl loadtest start orders-smoke` (or **Infra → Run**)
runs `k6 run -o experimental-prometheus-rw --tag testid=<name>`; Prometheus is started with the remote-write receiver
so k6 metrics (`k6_*`, filter `testid`) show up in Grafana next to your services' `/actuator/prometheus` metrics.
Needs `k6` on PATH and `prometheus` in `infra.enabled`. Output: **Logs → k6-<name>**.

## Logs in Loki
The `log-shipper` process (`devctl ship-logs`, stdlib, no Promtail/Alloy) tails `~/.localdev/logs/<process>.log`
(process-compose writes one per process via `log_location`), JBoss `server.log` and every build log, and pushes to
Loki's HTTP API. Grafana → Explore → Loki: `{job="devctl"}`, narrow with `service` (`orders-api`, `jboss-server`,
`build`, …). Existing log history at start-up is not replayed; if Loki is down lines are buffered (5000 per stream) and retried.

## Use it

| Task | Dashboard | CLI |
|---|---|---|
| pick repo + branch, build, watch log | **Build** tab (live log) | `devctl build <repo> <branch> --wait` |
| deploy a build to a service | **Services** tab → select build → Deploy | `devctl deploy <svc> [--build ID \| --branch B]` |
| start/stop/restart, stacks | row buttons / Stacks bar | `devctl start\|stop\|restart <svc>`, `devctl stack up <name>` |
| health | badge per service (polled every 5 s) | `devctl status` |
| process logs (incl. JBoss `server.log`) | **Logs** tab (follow) | `devctl logs <name>` |
| Grafana / Loki / Prometheus | **Infra** tab | `devctl infra start grafana` |
| terminal UI | — | `devctl tui` (process-compose TUI) |

Builds are detached processes: closing the dashboard or the agent session does not kill them.

## MCP (Claude Code, Cursor, …)

```bash
claude mcp add devctl -- /ABS/PATH/local-dev/bin/devctl-mcp
```
Cursor `~/.cursor/mcp.json`: `{"mcpServers":{"devctl":{"command":"/ABS/PATH/local-dev/bin/devctl-mcp"}}}`

Tools: `list_repos`, `list_branches`, `build`, `build_status`, `list_builds`, `deploy`, `undeploy`, `status`,
`service_control`, `stack_control`, `infra_control`, `logs`, `sync_project`. Example: *"build orders from
feature/x, deploy it to orders-api and tell me when it's healthy"*.

## Security
Dashboard binds `127.0.0.1` only, checks the `Host` header (DNS rebinding) and requires `X-Devctl: 1` on every
mutating call. Build commands come from your own `config.json` and run with your user rights — treat that file like a
shell profile. Branch names are validated (no option injection). Grafana runs with anonymous admin: local use only.

## Tests
`python3 -m unittest discover -s local-dev/tests` (no process-compose/Docker/JBoss needed).

## Verified vs. not
Unit/integration tests cover discovery, worktree builds, artifact collection, multi-WAR deploy, project generation,
MCP and the dashboard guards (Linux, Python 3.13). **k6, the Loki push and the generated project are tested without the real tools (a fake Loki HTTP server for the shipper). **Not yet run against a real process-compose, Docker or JBoss EAP
on a Mac**, nor k6 → Prometheus remote-write or the Loki datasource in Grafana — the process-compose CLI flags (`up -D --tui=false`, `project update`, `process list -o json`) follow its
documented v1.x CLI; check `devctl up` / `devctl status` first and see docs/open-questions.md OQ-LD-1.
