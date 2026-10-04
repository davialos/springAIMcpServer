# Universal performance test (`scripts/perf-test.sh`)

One command that load-tests **any running HTTP service** with Grafana k6 and, while the load runs, takes a Java
Flight Recording of the service's JVM, then analyzes it. It chains the two existing developer tools:

- the k6 suite generator, `scripts/loadtest.sh` ([LLD-16](../lld/16-load-test-generator.md));
- the JFR analyzer, `scripts/jfr-analyze.sh` ([jfr-analyzer.md](jfr-analyzer.md)).

The service does not need this library, Spring or any agent. It needs an HTTP API that can be described (OpenAPI,
`/actuator/mappings`, its sources, or a browser recording) and, for profiling, a HotSpot JVM on JDK 11 or later.

## Run it

```
# Minimal: discovers the API from <target>/v3/api-docs, smoke load, profiles the only JVM on this machine
scripts/perf-test.sh --target http://localhost:8080 -p com.acme.orders

# A service on another server, real data from its database, 20 VUs of a weighted mix
LOADTEST_DB_PASSWORD=… AUTH_TOKEN=… scripts/perf-test.sh \
  --target http://orders-1.staging:8080 --name orders-staging \
  --db-url jdbc:postgresql://orders-db.staging:5432/orders --db-user orders_ro --data-mode mixed \
  --mode mixed-load --vus 20 --warmup smoke \
  --ssh deploy@orders-1.staging --jcmd 'sudo -u orders jcmd' --jvm-match orders-service \
  -p com.acme.orders

# Everything from a configuration file; flags override it
scripts/perf-test.sh --env-file perf/orders-staging.env --mode stress
```

`scripts/perf-test.sh --help` lists every option. A commented example configuration is in
[`scripts/perf-test.env.example`](../../scripts/perf-test.env.example).

## Pipeline

| Step | What happens | Skipped by |
|---|---|---|
| 1. Configuration | `--env-file` files are sourced (bash `KEY=VALUE`), then flags are applied. Every option has a `PERF_<NAME>` variable. | — |
| 2. Preflight | `java`, `k6` and the tool that reaches the JVM are checked. With `--health-path`, waits until the target answers 2xx/3xx (`--health-timeout`, default 120 s). | `--dry-run` |
| 3. Discovery + generation | `loadtest.sh generate` writes the k6 suite (default `<out>/<name>/suite`, kept across runs so team edits to `loadtest.config.json`, `data/user.json` and `hooks.js` survive). Without `--openapi/--project/--actuator/--har`, the script fetches `<target>/v3/api-docs`, else `<target>/actuator/mappings`, and keeps that snapshot in the run folder. | `--skip-generate` (reuse the suite), `--skip-load` |
| 4. Warm-up | `--warmup <mode>` runs k6 once before recording, so JIT compilation is not what gets profiled. | no `--warmup` |
| 5. Recording | Finds the JVM, `jcmd <pid> JFR.start settings=profile duration=<cap> filename=<remote-dir>/perf-….jfr`. | `--no-jfr` |
| 6. Load | `loadtest.sh run --mode <mode> --base-url <target>` (k6); console in `k6.log`. | `--skip-load` (records for `--jfr-duration` s instead) |
| 7. Stop + fetch | `JFR.stop`, the file is copied to the run folder and deleted where the JVM runs. | — |
| 8. Analysis | `jfr-analyze.sh recording.jfr -p <packages> -o jfr/`. | — |
| 9. Summary | `summary.md`: parameters, k6 report, JFR findings, file list. | — |

## Inputs

**Target.** `--target` is the base URL including any context path (`http://srv:8080/shop`). `--name` names the
results folder (default `host-port`).

**API discovery.** Any combination of `--openapi URL|FILE`, `--project DIR` (Java sources, adds validation
constraints and JPA entities), `--actuator URL|FILE`, `--har FILE` (browser recording; adds a replayable journey for
`journey-<profile>` modes). Narrow with `--include` / `--exclude`. `--header 'Name: value'` is sent on discovery
fetches and harvesting; `AUTH_TOKEN` adds a bearer token.

**Data source.** The suite chooses each field's value per request by `--data-mode`
(`auto | dummy | random | real | user | mixed`). Real values come from:
- a database: `--db-url` (JDBC), `--db-user`, `--db-schema`, password in `LOADTEST_DB_PASSWORD`. When these are
  absent, `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` are used, so a service's own Spring env file can be
  passed with `--env-file` as the data source. Connections are read-only; PostgreSQL's driver is bundled, others go
  on `LOADTEST_CLASSPATH`;
- the running API: `--harvest` collects ids from parameterless list endpoints;
- your files and values: `--user-data FILE` (JSON/YAML/CSV), `--value key=v1,v2`, `--bind key=table.column`.

**Load.** `--mode` (`smoke`, `load`, `stress`, `spike`, `soak`, `breakpoint`, each also as `mixed-<p>` and
`journey-<p>`; `scripts/loadtest.sh modes` lists them), scaled by `--vus`, `--rate`, `--duration-scale`, narrowed by
`--api`, `--read-only`. Anything after `--` goes to k6, and k6's own variables work from the env file — e.g.
`K6_OUT=experimental-prometheus-rw` with `K6_PROMETHEUS_RW_SERVER_URL` streams metrics to Prometheus/Grafana.
The suite refuses targets matching `safety.blockedHostPattern`; `--allow-prod` passes `ALLOW_PROD=true`.

**Profiling.** Where the JVM runs:

| Location | Options | Commands used |
|---|---|---|
| this machine (default) | — | `jcmd`, `cp` |
| another server | `--ssh user@host [--ssh-opts '-i key -p 2222']` | `ssh … jcmd`, `scp` |
| a container | `--docker <container>` | `docker exec … jcmd`, `docker cp` |
| a Kubernetes pod | `--kube-pod <pod> [--kube-namespace ns] [--kube-container c]` | `kubectl exec … jcmd`, `kubectl cp` (needs `tar` in the image) |

Which JVM: `--jvm-pid`, or `--jvm-match <regex>` over `jcmd -l`; otherwise the only JVM there, or — on this machine
with several JVMs — the one listening on the target's port. `jcmd` must run as the service's user (attach is
per user): `--jcmd 'sudo -u orders jcmd'`. Images without `jcmd` (jlink'd or JRE-only) cannot be recorded this
way; start the service with `-XX:StartFlightRecording` instead and analyze the file with `jfr-analyze.sh`.

`-p/--package` names your code for hot-spot attribution (repeatable or comma-separated; without it every frame
counts as yours); `-x/--exclude-package` removes generated proxies. `--jfr-settings` (`profile` default),
`--jfr-exceptions` (throw sites, JDK 17+), `--jfr-remote-dir` (`/tmp`).

## Output

```
<out>/<name>/
  suite/                          k6 suite, regenerated and merged each run
  <yyyyMMdd-HHmmss>-<mode>/
    summary.md                    start here
    run.env                       parameters of this run (no secrets)
    openapi.json | actuator-mappings.json   discovery snapshot, when probed
    generate.log  k6.log          console output
    k6/<mode>-<time>.{md,json}    per-API latency/error report from the suite
    recording.jfr  jvm-version.txt  jfr-jcmd.log
    jfr/jfr-report.{html,json}    hot spots by method and line, GC, locks, I/O, findings
```

Exit code: k6's (`0`, `99` when thresholds failed, …); otherwise `1` if a step failed (generation, recording,
analysis) and `2` on a usage error. That makes it usable as a CI gate.

## Safety

- **The target is never left recording.** Every recording has a `duration` cap (`--jfr-max-duration`, default 3h)
  so the JVM ends it on its own, and an exit trap stops it on Ctrl-C or failure and saves the partial recording.
- **Secrets only from the environment** (`AUTH_TOKEN`, `AUTH_USER`/`AUTH_PASSWORD`, `API_KEY`,
  `LOADTEST_DB_PASSWORD`). They are never written to `run.env` or `summary.md`; logged commands mask `password=`
  and the values of `Authorization`, `Cookie`, `*api-key*` and `*token*` headers.
- **Recordings are sensitive.** JFR samples heap objects (e.g. `jdk.OldObjectSample` descriptions), so a recording
  can contain strings the service held, including request headers. Treat `recording.jfr` like a heap dump; the
  HTML/JSON reports only contain code locations and measurements.
- Writes are generated against the target as in LLD-16: DELETE is disabled by default, `--read-only` restricts the
  run to GET/HEAD. Point it at test environments, and use a read-only database account.
- `--dry-run` prints every command (credentials masked) and changes nothing.

## Requirements

Where the script runs: bash 4.4+, JDK 25 (`java` for the generator and analyzer; both build once, offline), k6,
curl (probing and `--health-path`), and `ssh`/`scp`, `docker` or `kubectl` for remote JVMs. Where the JVM runs:
HotSpot JDK 11+ with `jcmd`.
