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
- **Performance / profiling** (no new runtime dependency): services are polled for their own Micrometer metrics
  (`/actuator/prometheus`, falling back to `/actuator/metrics`, then a probe) - the minimal service change is actuator +
  Prometheus registry. Every devctl-started JVM runs a continuous JFR ring buffer (`-XX:StartFlightRecording`),
  dumped on demand with `jcmd`; analysis reuses `scripts/jfr-analyze.sh` and the dashboard renders its
  `jfr-analyzer/summary/1` JSON. A load run is one detached process (JFR start → k6 → metrics timeline → JFR stop →
  analysis) like a build, so k6 no longer runs as a process-compose process.
- **AI**: one tool catalogue (`tools.py`, each tool tagged read/write/destroy) serves the MCP server over stdio
  (default: every agent supports it, no daemon, no header auth - which several clients still drop) and stateless
  Streamable HTTP on the dashboard (`/mcp`, loopback + Host/Origin checks, optional bearer token), and the dashboard
  Assistant (official `anthropic` SDK as an optional dependency in `local-dev/.venv`, manual tool loop so write/destroy
  calls wait for the developer's approval). Goal-level tools (`ship`, `diagnose`, `wait_*`) replace agent polling loops;
  `initialize` instructions, resources and prompts teach agents the workflow. `devctl setup` is the single entry point.

## Consequences
Not a Spring module: no effect on the starter, BOM or offline repo. process-compose CLI behaviour is verified
only against its documentation until run on a Mac (OQ-LD-1). Jars only run as processes; WAR health is observed
by HTTP probe, not as separate process-compose processes.
