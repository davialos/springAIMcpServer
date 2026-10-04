---
name: loadtest-capacity
description: Measure how much load a Spring Boot application sustains, per API and for the realistic mix, with k6 stress and breakpoint runs. Use when someone asks for max throughput, capacity planning, "how many users can we handle", the breaking point, SLO headroom, or sizing before a launch.
---

# Capacity of a Spring application

Goal: for the weighted mix and for each critical API, the highest request rate that still meets the SLO
(default: p95 under the API's threshold and under 1 % errors), the first resource that saturates, and the
headroom over expected traffic.

## Before running

- Smoke must pass (`loadtest-generate` skill).
- **Confirm the environment with the user.** Capacity runs push the application until it fails: never against
  production or shared environments. Ask for the expected peak traffic and the SLO if not in
  `loadtest.config.json → thresholds / apis.<id>.p95Ms`.
- Start the Grafana stack (`docker compose -f <suite>/grafana/docker-compose.yml up -d`) and run with
  `grafana: true`, so saturation is visible next to the load.
- Keep data stable between runs: same data mode (`real` or `dummy`), `SEED_PER_TABLE` large enough that hot rows do
  not dominate (≥ 50 for writes), and the same app version and configuration.

## Runs

1. **Mix, step ramp**: `loadtest_run mode=mixed-stress` (1×, 2×, 3×, 4× base VUs, 3 minutes each). Scale with
   `env: {VUS: "<base>"}` until the last step fails. Record per step: rps, p95, errors.
2. **Mix, breakpoint**: `mode=mixed-breakpoint` ramps the arrival rate and aborts at the first failed threshold;
   `env: {RATE: "<start rps>"}`. The rate at abort time is the breaking point; capacity is the last rate whose
   1-minute window met the SLO.
3. **Per API** for the critical ones: `mode=breakpoint, api: "<id>"`. Isolates which endpoint caps the system.
4. **Soak** (optional): `mode=mixed-soak` at ~70 % of capacity for an hour reveals leaks and pool creep.

Between runs give the app a minute to recover; compare each run's report to the previous one with
`loadtest_compare` to make sure the system did not degrade.

## What limits it

Use the queries in the `loadtest-analyze` skill at the knee of the curve: pending HikariCP connections, busy
Tomcat threads at max, CPU near 1 per core, GC pause share above ~5 %, or one slow URI dominating. Name the first
one that saturates; that is what to fix or scale.

## Report

| Scope | Capacity (rps at SLO) | Breaking point | p95 at capacity | First saturated resource | Headroom vs peak |
|---|---|---|---|---|---|
| mix | | | | | |
| `<api>` | | | | | |

Add the exact commands and environment (instance size, replicas, pool sizes, data volume) so the numbers can be
reproduced, and keep the capacity run's report as the baseline for later regression gates.
