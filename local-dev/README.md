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
| `infra.enabled` | any of `prometheus`, `loki`, `grafana`, `postgres` (docker containers; Prometheus auto-scrapes every jar service on `/actuator/prometheus`; Grafana comes with Prometheus + Loki datasources) |

### Multiple WARs on JBoss EAP
All `kind: war` services share **one** JBoss EAP process (`jboss-eap` in process-compose) using a private base dir
(`~/localdev/jboss/standalone`, copied once from `$JBOSS_HOME/standalone`, so the install is never modified).
Deploying a WAR copies it to `deployments/<context>.war` (hot deploy); undeploy removes it. Readiness is
`http://127.0.0.1:9990/health/ready` (+ `port_offset`). Each WAR gets its own health URL
(default `http://localhost:8080/<context>/`). Different Java versions per service: set `java_home` on `jboss`.

## CEL Faker / Flow Studio (built-in service)
The checkout that contains `local-dev/` also contains the **CEL faker** (`spring-ai-mcp-server-common-celfaker`, ADR-0030): import a service's
Swagger / OpenAPI or a cURL, generate fake input, draw API flows and export a k6 suite. `devctl` runs it as a built-in `command` service named
**`celfaker`** — a process-compose process on `http://localhost:8110` with a readiness probe, logs, start/stop/restart, and:

- a **Faker** tab in the dashboard that embeds the studio (the faker allows framing only from the dashboard's loopback origin) plus an "open in new tab" link;
- one-click import: the other services of your `config.json` (jar/war/command with a port) are handed to the studio as **“Running locally”** chips on its Swagger import card;
- `devctl start|stop|restart|logs celfaker` and the MCP `service_control` tool work like for any service.

It runs `scripts/celfaker.sh` with **JDK 25** (`brew bundle` installs `openjdk@25`; `auto:25` also finds Homebrew's keg-only JDK) and compiles itself with Maven
on first start (a minute or two; the probe waits ~10 min). Config (all optional):
```json
"celfaker": {"enabled": true, "port": 8110, "autostart": true, "java_home": "auto:25", "repo_root": ""}
```
`autostart: false` keeps it stopped until you press Start; `enabled: false` removes it; defining your own `services.celfaker` replaces the built-in. The service is derived at load time and never written to `config.json`.
Guide: `docs/integration/celfaker-guide.md`.

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
MCP and the dashboard guards (Linux, Python 3.13). **Not yet run against a real process-compose, Docker or JBoss EAP
on a Mac** — the process-compose CLI flags (`up -D --tui=false`, `project update`, `process list -o json`) follow its
documented v1.x CLI; check `devctl up` / `devctl status` first and see docs/open-questions.md OQ-LD-1.
