# ADR-0029: Local development control plane on process-compose

Status: Accepted (2026-10-08) · Scope: `local-dev/` (outside the Maven reactor)

## Context
Developers run many repos locally, with mixed deployables (Spring Boot jars, several WARs on one JBoss EAP) and
support services (Grafana, Loki, Prometheus, Postgres, k6). They need branch-selectable local builds, deploys,
logs and health checks from a TUI, a web UI and AI agents (Claude, Cursor).

## Decision
- **process-compose** is the runtime supervisor (TUI, restarts, readiness probes, dependencies, REST). `devctl`
  generates its project file from `config.json` + deployed-state and applies it with `project update`.
- `devctl` is **Python stdlib only** (ships with macOS dev tooling, no install step, no build step). One function
  layer (`ops`, `builds`) is shared by CLI, dashboard and a stdio MCP server; all state is files (`meta.json`,
  `build.log`, `state.json`) so the three front ends never disagree and builds outlive any front end.
- Builds use a **git worktree per repo@branch** (never touches the developer's checkout); artifacts are copied into
  a configurable build-placement folder.
- All `war` services share **one JBoss EAP process with a private base dir**; deploy = copy into `deployments/`.
- Infra runs as docker containers under process-compose; Prometheus targets are generated from the services.
- Dashboard is loopback-only with Host and `X-Devctl` header checks because it can execute configured commands.
- **k6** runs as on-demand process-compose processes writing to Prometheus remote-write; **logs** go to Loki via a
  small stdlib shipper tailing process-compose `log_location` files (chosen over Promtail/Alloy: testable here, no extra image).

## Consequences
Not a Spring module: no effect on the starter, BOM or offline repo. process-compose CLI behaviour is verified
only against its documentation until run on a Mac (OQ-LD-1). Jars only run as processes; WAR health is observed
by HTTP probe, not as separate process-compose processes.
