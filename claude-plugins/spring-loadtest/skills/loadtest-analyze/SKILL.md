---
name: loadtest-analyze
description: Explain the results of a k6 load test of a Spring Boot application and find the bottleneck with evidence. Use after a load/stress/spike run, when p95 latency or errors went up, when a regression gate failed, or when someone asks why an endpoint is slow under load. Correlates the per-API report, baseline comparisons, Grafana/Prometheus metrics (HikariCP, GC, CPU, threads) and JFR hot spots down to the code.
---

# Analyze a load-test run

Goal: a short, ranked list of findings. Each finding names the API, the measured symptom, the cause with evidence
(numbers, metric names, `file:line`), and the smallest fix worth trying. Say "unknown" rather than guess.

## 1. Read the numbers

- `loadtest_report` (or `scripts/loadtest.sh report --suite <suite> [--mode <mode>]`): totals, per-API requests,
  failed share, p95/p99, failed thresholds.
- With a baseline: `loadtest_compare` (`loadtest compare --baseline <file>`): regressions are APIs whose p95 rose
  more than the rule (default +20 % and at least 10 ms) or whose failed share rose more than 1 point. Ignore
  `too-few-requests` rows.
- Separate the failure kinds: errors (4xx/5xx, timeouts) vs latency. Errors under load that are absent in smoke
  usually mean exhaustion (connection pool, threads, rate limiter) or data contention (unique keys, locks).

## 2. Look at the application while it was under load

When the suite's Grafana stack ran (`docker compose -f <suite>/grafana/docker-compose.yml up -d` and
`grafana: true` / `GRAFANA=1`), query Prometheus at `http://localhost:9090/api/v1/query` for the run's window
(the run's `testid` label is in the run result; the Grafana annotation marks start and end):

| Question | PromQL |
|---|---|
| Which URIs are slow on the server? | `sum by (uri)(rate(http_server_requests_seconds_sum{job="spring-app"}[1m])) / sum by (uri)(rate(http_server_requests_seconds_count{job="spring-app"}[1m]))` |
| Is the DB pool the bottleneck? | `max(hikaricp_connections_pending{job="spring-app"})` > 0, `hikaricp_connections_active` at `hikaricp_connections_max` |
| GC pressure? | `sum(rate(jvm_gc_pause_seconds_sum{job="spring-app"}[1m]))` (seconds of pause per second) |
| CPU bound? | `max(process_cpu_usage{job="spring-app"})` near 1 per core |
| Thread starvation? | `tomcat_threads_busy_threads` at its max, or `executor_active_threads` flat at the pool size |
| Client vs server time | k6 `k6_http_req_duration_p95{testid="…"}` vs server avg: a large gap is network, proxy or client saturation |

`up{job="spring-app"} == 0` means Prometheus could not scrape the app: fix `grafana/prometheus.yml` (needs
`micrometer-registry-prometheus` and `management.endpoints.web.exposure.include=prometheus`).

## 3. Find the code

- **JFR**: record during a stress run (`jcmd <pid> JFR.start settings=profile duration=3m filename=load.jfr`) and
  analyze with `$LOADTEST_HOME/scripts/jfr-analyze.sh load.jfr -p <app base package>`; its report pins CPU,
  allocation, lock and blocking-I/O costs to methods and lines.
- **N+1 queries**: a list endpoint whose latency grows with page size, many short JDBC calls in JFR; look for lazy
  `@OneToMany`/`@ManyToOne` access in the controller/service path (fix: fetch join, `@EntityGraph`, DTO projection).
- **Missing index**: one slow query per request on a filtered/sorted column; check the Flyway/Liquibase DDL for an
  index on the columns used in `WHERE`/`ORDER BY` of the repository method.
- **Pool sizing**: pending connections with low DB CPU → raise `spring.datasource.hikari.maximum-pool-size` only
  after removing long transactions (`@Transactional` around remote calls, open-session-in-view).

## 4. Report

| # | API | Symptom | Evidence | Cause | Suggested fix |
|---|---|---|---|---|---|

Then: what to rerun to confirm (same mode and data mode, same `VUS`/`RATE`, against the same baseline).
