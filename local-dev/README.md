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
| `loadtests.<name>` | k6 script (relative to `loadtest_dir`) + `service`, `profile`, `vus`, `duration`, `env`, `jfr`; usually created by the wizard |
| `jfr` | `auto`, `settings`, `maxage`, `maxsize`, `packages`, `auto_analyze`, `analyzer_cmd`, `analyzer_java_home` (override per service under `services.<name>.jfr`) |
| `metrics.interval_seconds` | live-metrics poll interval (default 2) |
| `infra.ship_logs` / `loki_url` | ship all process, JBoss and build logs to Loki (default on when `loki` is enabled) |
| `infra.enabled` | any of `prometheus`, `loki`, `grafana`, `postgres` (docker containers; Prometheus auto-scrapes every jar service on `/actuator/prometheus`; Grafana comes with Prometheus + Loki datasources) |

### Multiple WARs on JBoss EAP
All `kind: war` services share **one** JBoss EAP process (`jboss-eap` in process-compose) using a private base dir
(`~/localdev/jboss/standalone`, copied once from `$JBOSS_HOME/standalone`, so the install is never modified).
Deploying a WAR copies it to `deployments/<context>.war` (hot deploy); undeploy removes it. Readiness is
`http://127.0.0.1:9990/health/ready` (+ `port_offset`). Each WAR gets its own health URL
(default `http://localhost:8080/<context>/`). Different Java versions per service: set `java_home` on `jboss`.

## Performance, load testing and profiling

Three dashboard tabs, also available from the CLI and MCP:

| Tab | What it shows | Needs in the service |
|---|---|---|
| **Performance** | live view of any service, polled every 2 s from its own endpoints: req/s, avg + p95 latency, 5xx %, heap, CPU, GC time, threads, DB pool, Tomcat busy threads, error log lines, health-probe latency, busiest endpoints | ideally `/actuator/prometheus`; plain `/actuator/metrics` works (no p95, no endpoint table); with neither you only get up/down and probe latency |
| **Load tests** | setup wizard (discover endpoints → pick + weight → profile smoke/load/stress/spike/soak, VUs, thresholds → k6 script), runs with live progress, results (k6 client figures, service-side avg/peaks + charts, thresholds, JFR verdict, log) | `/v3/api-docs` (springdoc) or the `mappings` actuator endpoint for discovery |
| **Profiling** | local JVMs (`jps`), snapshot / timed recordings, all recordings with analysis status, and a clean view of each analysis: status + score, key metrics, top issues with the action to take, recommendations, CPU/allocation/lock/exception hot spots (`Class.method(File.java:line)`), GC and memory facts. Links to the analyzer's full interactive HTML report and Excel | nothing (JVM flags only) |

**Minimal Spring Boot change** (the **Check readiness** button tests each item and shows the fix):
```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId><scope>runtime</scope></dependency>
<!-- optional, endpoint discovery: org.springdoc:springdoc-openapi-starter-webmvc-ui -->
```
```properties
# application-local.properties (args: --spring.profiles.active=local)
management.endpoints.web.exposure.include=health,info,metrics,prometheus,mappings
management.metrics.distribution.percentiles-histogram.http.server.requests=true   # p95/p99
```
For a service run from the IDE or elsewhere, set `services.<name>.base_url` (and `port`); a service with a context path
needs `base_url` including it. `metrics.prometheus` overrides the scrape URL (e.g. a JBoss/WildFly `/metrics`).

### Automatic JFR recording
Every JVM devctl starts (jar services and JBoss) gets `-Ddevctl.service=<name>` and, unless `jfr.auto` is false
(globally or per service), a continuous recording:
`-XX:StartFlightRecording:name=devctl,settings=profile,maxage=30m,maxsize=256m,dumponexit=true,…`. The JVM keeps the
last 30 minutes in a ring buffer: **Snapshot** dumps it (`jcmd JFR.dump`), stopping the service writes
`<svc>-exit-<time>.jfr`. **Record N s** works on any local JVM by pid (`jcmd JFR.start duration=…`). Files go to
`<builds_dir>/jfr/<service>/`.

Analysis uses this repository's analyzer (`scripts/jfr-analyze.sh`, docs/tools/jfr-analyzer.md; built offline on
first use, needs **JDK 25**, set `jfr.analyzer_java_home`, default `auto:25`). `jfr.packages` (global or per service)
are the `-p` packages costs are attributed to, e.g. `["com.acme.orders"]`. Snapshots and timed/load-test recordings
are analyzed automatically (`jfr.auto_analyze`). Outside this repository set `jfr.analyzer_cmd`.

### A load-test run, end to end
`devctl loadtest run orders-load [--vus 50 --duration 5m]`, **Run** in the dashboard, or the `loadtest_run` MCP tool:
1. JFR recording started on the target JVM (`loadtests.<name>.jfr`, default on);
2. `k6 run --summary-export …` (plus Prometheus remote-write → Grafana **devctl · k6** when `prometheus` is enabled),
   while the target's metrics are sampled every 2 s into `timeline.json`;
3. JFR stopped, saved and analyzed; everything lands in `<builds_dir>/loadruns/<id>/` (meta, log, k6 summary, timeline).

Status: `passed`, `thresholds_failed` (k6 exit 99), `failed`, `stopped` (k6 is interrupted gracefully, JFR still saved).
`loadtests.<name>` = `{script, service, profile, vus, duration, env, jfr}`; scripts from the full data-driven generator
(`scripts/loadtest.sh`, `scripts/perf-test.sh`) can be registered the same way.

## Grafana dashboards (provisioned, folder `devctl`)
- **devctl · k6 load test** — VUs, req/s, p95 / error-rate / checks stats with thresholds, response-time (avg/p95/p99/max)
  and time-breakdown charts, failed requests, network, and the services' error lines per minute from Loki so you can
  see a latency spike next to the errors it caused. Variable `testid` picks the run.
- **devctl · logs** — volume per service, errors/warnings per minute, failed builds, JBoss deploy events, live log
  stream with `service` filter and a free-text box, errors-only and build-output panels.
They are copied from `devctl/dashboards/*.json` on every `devctl up`/sync (generated by `dashboards/generate.py <outdir>`);
to customise, "Save as" a copy in Grafana. The k6 metric names (`k6_http_req_duration_p95`, `k6_http_req_failed_rate`, …)
are those of k6's experimental Prometheus output with trend stats `avg,p(95),p(99),max` — not yet seen on a live run.

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
| live performance / readiness | **Performance** tab | `devctl perf <svc>`, `devctl readiness <svc>` |
| load test | **Load tests** tab | `devctl loadtest discover <svc> \| run <name> \| runs \| show <run> \| stop <run>` |
| JFR | **Profiling** tab | `devctl jfr jvms \| snapshot <svc> \| record <svc\|pid> --seconds 60 \| list \| analyze <id> \| summary <id>` |
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
`service_control`, `stack_control`, `infra_control`, `logs`, `sync_project`, `perf_snapshot`, `perf_readiness`,
`loadtest_discover`, `loadtest_create`, `loadtest_run`, `loadtest_result`, `loadtest_runs`, `loadtest_stop`,
`jfr_list_jvms`, `jfr_snapshot`, `jfr_record`, `jfr_list`, `jfr_analyze`, `jfr_summary`. Example: *"build orders from
feature/x, deploy it to orders-api and tell me when it's healthy"*, *"load test orders-api at 50 VUs for 2 minutes and
tell me the slowest code line from the JFR"*.

## Security
Dashboard binds `127.0.0.1` only, checks the `Host` header (DNS rebinding) and requires `X-Devctl: 1` on every
mutating call. Build commands come from your own `config.json` and run with your user rights — treat that file like a
shell profile. Branch names are validated (no option injection). Grafana runs with anonymous admin: local use only.

## Tests
`python3 -m unittest discover -s local-dev/tests`. `test_perf.EndToEndTest` needs a JDK on PATH: it starts a real JVM
(`tests/fixtures/Demo.java`) with devctl's JFR flags and runs live sampling, readiness, discovery, a full load run
(`tests/fixtures/fake_k6.py` stands in for k6), snapshot and, with a JDK 25 available, the real analyzer.

## Verified vs. not
Verified here (Linux, JDK 21 app + JDK 25 analyzer): automatic JFR recording, snapshot, load-run JFR + analysis, live
Prometheus sampling incl. p95 from buckets, OpenAPI discovery, script generation (syntax-checked with node), the
dashboard tabs (rendered in Chromium, light/dark/phone width). k6 itself was replaced by a fake: real k6 output
parsing follows its `--summary-export` format.
Unit/integration tests cover discovery, worktree builds, artifact collection, multi-WAR deploy, project generation,
MCP and the dashboard guards (Linux, Python 3.13). k6 config, the Loki push (against a fake Loki server) and project generation are tested. **Not yet run against a real process-compose, Docker or JBoss EAP
on a Mac**, nor k6 → Prometheus remote-write or the Loki datasource in Grafana — the process-compose CLI flags (`up -D --tui=false`, `project update`, `process list -o json`) follow its
documented v1.x CLI; check `devctl up` / `devctl status` first and see docs/open-questions.md OQ-LD-1.
