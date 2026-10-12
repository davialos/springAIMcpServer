"""The one tool catalogue, shared by the MCP server (stdio + HTTP) and the dashboard's AI assistant.

Each tool: description, JSON-schema properties, required keys, implementation, and an effect level:
  read     - only reads state (safe to call any time)
  write    - changes local state, reversible (build, deploy, start/stop, run a load test, record JFR, edit config)
  destroy  - removes something (undeploy, delete config entries)
The assistant asks the user before ``write``/``destroy`` calls; MCP clients get the same information as tool annotations.
"""
from __future__ import annotations

import json

from . import agentops, builds, config, jfr, jvm, loadgen, loadrun, metrics, ops, pc, repos
from .lab import flow as labflow, service as lab, store as labstore

S, I, B = {"type": "string"}, {"type": "integer"}, {"type": "boolean"}


def _t(desc, fn, props=None, req=(), effect="read"):
    return {"description": desc, "fn": fn, "props": props or {}, "required": list(req), "effect": effect}


def _run_summary(r: dict) -> dict:
    fails = [s for it in r["iterations"] for s in it if not s["ok"]][:5]
    return {"run_id": r["id"], "workflow": r["workflow"], "ok": r["ok"], "failed": r["failed"], "per_step": r["summary"],
            "failures": [{"step": s["step"], "api": s["api"], "status": s.get("status"), "url": s.get("url"), "error": s.get("error"),
                          "warnings": s.get("warnings"), "request_body": s["request"].get("body"),
                          "response": str(s.get("response"))[:600]} for s in fails]}


P = {"type": "string", "description": "API Lab project name"}


def _cfg_view(c, a):
    sec = a.get("section", "")
    return c.get(sec, {}) if sec else {k: c[k] for k in ("workspace", "builds_dir", "services", "repos", "stacks", "loadtests", "jfr", "infra", "jboss")}


TOOLS = {
    # --- orientation
    "status": _t("Everything at a glance: every service (kind, deployed build, process state, health), stacks, infra, JBoss. "
                 "Start here.", lambda c, a: ops.status(c)),
    "diagnose": _t("One-call investigation of a service: process, health, deployed build, live metrics, busiest endpoints, recent "
                   "error log lines, last build, last load run, last JFR verdict, and hints. Use before guessing.",
                   lambda c, a: agentops.diagnose(c, a["service"]), {"service": S}, ["service"]),
    "config_get": _t("Read the devctl configuration (optionally one section: services, repos, stacks, loadtests, jfr, infra, jboss).",
                     _cfg_view, {"section": S}),
    # --- repos & builds
    "list_repos": _t("Git repositories found in the workspace folder (name, build system, checked-out branch).",
                     lambda c, a: repos.discover(c)),
    "list_branches": _t("Branches of a repository, newest first (fetch=true runs git fetch first).",
                        lambda c, a: repos.branches(c, a["repo"], a.get("fetch", False)), {"repo": S, "fetch": B}, ["repo"]),
    "build": _t("Start a build of repo@branch in an isolated git worktree (never touches the developer's checkout). Returns the "
                "build id at once; then call wait_build.", lambda c, a: builds.start(c, a["repo"], a["branch"]),
                {"repo": S, "branch": S}, ["repo", "branch"], "write"),
    "wait_build": _t("Wait (up to timeout_seconds, default 600) for a build to finish; returns status, artifacts and the log tail.",
                     lambda c, a: agentops.wait_build(c, a["build_id"], int(a.get("timeout_seconds", 600))),
                     {"build_id": S, "timeout_seconds": I}, ["build_id"]),
    "build_status": _t("Status of a build plus the end of its log (no waiting).",
                       lambda c, a: {**builds.read_meta(c, a["build_id"]), "log_tail": builds.read_log(c, a["build_id"])["text"][-4000:]},
                       {"build_id": S}, ["build_id"]),
    "list_builds": _t("Recent builds, newest first.", lambda c, a: builds.list_builds(c, 20, a.get("repo", ""), a.get("only_success", False)),
                      {"repo": S, "only_success": B}),
    # --- deploy & run
    "ship": _t("Build repo@branch, deploy it to a service and wait until healthy - the usual 'run my branch locally' in one call.",
               lambda c, a: agentops.ship(c, a["repo"], a["branch"], a["service"], int(a.get("timeout_seconds", 900))),
               {"repo": S, "branch": S, "service": S, "timeout_seconds": I}, ["repo", "branch", "service"], "write"),
    "deploy": _t("Deploy a build's artifact to a service (jar -> its own process, war -> JBoss EAP deployments). Without build_id "
                 "the newest successful build (optionally of branch) is used.",
                 lambda c, a: ops.deploy(c, a["service"], a["build_id"]) if a.get("build_id") else ops.deploy_latest(c, a["service"], a.get("branch", "")),
                 {"service": S, "build_id": S, "branch": S}, ["service"], "write"),
    "undeploy": _t("Undeploy a service (stops it and removes the deployed artifact).", lambda c, a: ops.undeploy(c, a["service"]),
                   {"service": S}, ["service"], "destroy"),
    "wait_healthy": _t("Wait (default 180 s) until a service's health check passes; returns recent error lines if it does not.",
                       lambda c, a: agentops.wait_healthy(c, a["service"], int(a.get("timeout_seconds", 180))),
                       {"service": S, "timeout_seconds": I}, ["service"]),
    "service_control": _t("start | stop | restart a service (WAR services act on the shared JBoss).",
                          lambda c, a: ops.service_control(c, a["service"], a["action"]),
                          {"service": S, "action": {"type": "string", "enum": ["start", "stop", "restart"]}}, ["service", "action"], "write"),
    "stack_control": _t("up | down a stack (services that run together, e.g. several WARs).",
                        lambda c, a: ops.stack_control(c, a["stack"], a["action"]),
                        {"stack": S, "action": {"type": "string", "enum": ["up", "down"]}}, ["stack", "action"], "write"),
    "infra_control": _t("start | stop | restart prometheus, loki, grafana or postgres (docker).",
                        lambda c, a: ops.infra_control(c, a["name"], a["action"]),
                        {"name": {"type": "string", "enum": ["prometheus", "loki", "grafana", "postgres"]},
                         "action": {"type": "string", "enum": ["start", "stop", "restart"]}}, ["name", "action"], "write"),
    "sync_project": _t("Regenerate and apply the process-compose project (starts it headless if not running).",
                       lambda c, a: pc.sync(c), effect="write"),
    # --- logs
    "logs": _t("Last N lines of a process log (a service, jboss-eap, grafana, log-shipper ...).",
               lambda c, a: agentops.read_logs(c, a["name"], int(a.get("lines", 150))), {"name": S, "lines": I}, ["name"]),
    "search_logs": _t("Regex search in a process log (case-insensitive); context = lines around each match.",
                      lambda c, a: agentops.search_logs(c, a["name"], a["pattern"], int(a.get("lines", 60)), int(a.get("context", 0))),
                      {"name": S, "pattern": S, "lines": I, "context": I}, ["name", "pattern"]),
    # --- performance
    "perf_snapshot": _t("Live performance right now (2 s window): req/s, avg/p95 latency, 5xx %, heap, CPU, threads, GC, pool and the "
                        "busiest endpoints, read from the service's /actuator/prometheus (or /actuator/metrics).",
                        lambda c, a: metrics.snapshot(c, a["service"]), {"service": S}, ["service"]),
    "perf_readiness": _t("Which observability / load-test endpoints a service exposes, with the minimal Spring change for each missing one.",
                         lambda c, a: metrics.readiness(c, a["service"]), {"service": S}, ["service"]),
    # --- load tests
    "loadtest_discover": _t("Discover a service's HTTP endpoints (OpenAPI /v3/api-docs or /actuator/mappings).",
                            lambda c, a: loadgen.discover(c, a.get("service", ""), a.get("base_url", "")), {"service": S, "base_url": S}),
    "loadtest_create": _t("Generate a k6 script and register it as a load test. endpoints: [{method, path, weight?}]; "
                          "profile smoke|load|stress|spike|soak; path_values {param: [values]}; p95_ms; max_error_rate (0.01 = 1%).",
                          lambda c, a: loadgen.create(c, a["name"], a["service"], a["endpoints"],
                                                      **{k: v for k, v in a.items() if k not in ("name", "service", "endpoints")}),
                          {"name": S, "service": S, "endpoints": {"type": "array", "items": {"type": "object"}}, "profile": S,
                           "vus": I, "duration": S, "path_values": {"type": "object"}, "p95_ms": I, "max_error_rate": {"type": "number"}},
                          ["name", "service", "endpoints"], "write"),
    "loadtest_run": _t("Run a load test end to end (JFR on the target, k6, live service metrics, JFR analysis). Returns the run id; "
                       "poll loadtest_result.", lambda c, a: loadrun.start(c, a["name"], int(a.get("vus") or 0), a.get("duration", "")),
                       {"name": S, "vus": I, "duration": S}, ["name"], "write"),
    "loadtest_result": _t("Status and results of a load run: k6 figures, service-side averages/peaks, thresholds, JFR verdict.",
                          lambda c, a: loadrun.read(c, a["run_id"]), {"run_id": S}, ["run_id"]),
    "loadtest_runs": _t("Recent load runs.", lambda c, a: loadrun.list_runs(c, 20)),
    "loadtest_stop": _t("Stop a running load run (k6 interrupted gracefully; JFR still saved and analyzed).",
                        lambda c, a: loadrun.stop(c, a["run_id"]), {"run_id": S}, ["run_id"], "write"),
    # --- profiling
    "jfr_list_jvms": _t("Local JVMs: pid, main class, devctl service name if devctl started it.", lambda c, a: jvm.list_jvms(c)),
    "jfr_snapshot": _t("Save the continuous JFR recording (last jfr.maxage, default 30 min) of a service or pid now; analysis starts automatically.",
                       lambda c, a: jfr.snapshot(c, a.get("service", ""), int(a.get("pid") or 0)), {"service": S, "pid": I}, effect="write"),
    "jfr_record": _t("Timed JFR recording (seconds, default 60) of a service or any local JVM pid.",
                     lambda c, a: jfr.record(c, int(a.get("seconds", 60)), a.get("service", ""), int(a.get("pid") or 0)),
                     {"service": S, "pid": I, "seconds": I}, effect="write"),
    "jfr_list": _t("JFR recordings with analysis status, health and score.", lambda c, a: jfr.list_recordings(c, a.get("service", "")),
                   {"service": S}),
    "jfr_analyze": _t("(Re)analyze a recording.", lambda c, a: jfr.analyze(c, a["id"]), {"id": S}, ["id"], "write"),
    "jfr_summary": _t("Analysis of a recording: status, score, key metrics, top issues with actions, hot spots with file:line, GC, memory.",
                      lambda c, a: jfr.summary(c, a["id"]), {"id": S}, ["id"]),
    # --- API Lab: test data, threads, workflows (JSON files under apilab_dir/<project>/)
    "apilab_projects": _t("API Lab projects with counts (APIs, threads, accepted threads, workflows).", lambda c, a: lab.projects(c)),
    "apilab_create_project": _t("Create/update an API Lab project for a repo (workspace repo name or path) and/or a running service "
                                "(base_url from the service). headers: default request headers, e.g. {\"Authorization\": \"Bearer ...\"}.",
                                lambda c, a: lab.create(c, a["name"], a.get("repo", ""), a.get("service", ""), a.get("base_url", ""), a.get("headers")),
                                {"name": S, "repo": S, "service": S, "base_url": S, "headers": {"type": "object"}}, ["name"], "write"),
    "apilab_discover": _t("Discover the project's APIs (source auto|openapi|source|mappings: OpenAPI of the running service, Spring "
                          "controllers + DTOs from the repo, or actuator mappings) and suggest threads between them.",
                          lambda c, a: lab.run_discovery(c, a["project"], a.get("source", "auto")), {"project": P, "source": S}, ["project"], "write"),
    "apilab_apis": _t("APIs of a project: id, method, path, field counts, rules, generated rows, accepted incoming threads.",
                      lambda c, a: lab.api_list(c, a["project"]), {"project": P}, ["project"]),
    "apilab_api": _t("One API in full: request fields (path/query/header/body with types, formats, constraints), response fields, "
                     "fake-data rules, incoming/outgoing threads and a sample payload.",
                     lambda c, a: lab.api_detail(c, a["project"], a["api"]), {"project": P, "api": S}, ["project", "api"]),
    "apilab_links": _t("Threads (response field of one API -> request field of another) with status suggested|accepted|rejected, "
                       "confidence and reason. Filter by status, api, min_confidence.",
                       lambda c, a: lab.links_view(c, a["project"], a.get("status", ""), a.get("api", ""), float(a.get("min_confidence", 0))),
                       {"project": P, "status": S, "api": S, "min_confidence": {"type": "number"}}, ["project"]),
    "apilab_link_set": _t("Create or decide one thread: from_api's response field from_field feeds to_api's request field to_field "
                          "(fields exactly as apilab_api lists them, e.g. 'id' -> 'body.customerId' or 'path.id'). status accepted "
                          "(default) | suggested | rejected. Give a short reason.",
                          lambda c, a: lab.link_set(c, a["project"], a["from_api"], a["from_field"], a["to_api"], a["to_field"],
                                                    a.get("status", "accepted"), a.get("reason", "")),
                          {"project": P, "from_api": S, "from_field": S, "to_api": S, "to_field": S, "status": S, "reason": S},
                          ["project", "from_api", "from_field", "to_api", "to_field"], "write"),
    "apilab_link_status": _t("Accept, reject, reset (suggested) or delete several threads by id at once.",
                             lambda c, a: lab.link_status(c, a["project"], a["ids"], a["status"]),
                             {"project": P, "ids": {"type": "array", "items": {"type": "string"}},
                              "status": {"type": "string", "enum": ["accepted", "rejected", "suggested", "delete"]}},
                             ["project", "ids", "status"], "write"),
    "apilab_set_rules": _t("Fake-data rules for an API's request fields: {field_path: rule}. Rule keys: kind (email, first_name, "
                           "full_name, phone, company, city, country, iso_country, street, zip, uuid, date, datetime, past_date, "
                           "future_date, url, word, words, sentence, paragraph, int, decimal, money, bool, currency, sku, code, ip, color, "
                           "username, password, iban, lat, lon, time), enum, const, pattern ('ORD-####'), template "
                           "('user{{seq}}@x.test'), min/max, pool ('<api>.<response field>'), null_rate, omit. Returns a preview.",
                           lambda c, a: lab.set_rules(c, a["project"], a["api"], a["rules"], a.get("replace", False)),
                           {"project": P, "api": S, "rules": {"type": "object"}, "replace": B}, ["project", "api", "rules"], "write"),
    "apilab_generate": _t("Generate and save fake payloads (data/<api>.json) for one API or all; threads become {$link} markers or "
                          "real values captured from earlier runs.",
                          lambda c, a: lab.generate(c, a["project"], a.get("api", ""), int(a.get("count", 20))),
                          {"project": P, "api": S, "count": I}, ["project"], "write"),
    "apilab_data": _t("Generated payloads of an API (and the file they live in).",
                      lambda c, a: lab.data_view(c, a["project"], a["api"], int(a.get("limit", 20))), {"project": P, "api": S, "limit": I},
                      ["project", "api"]),
    "apilab_workflow_auto": _t("Build a workflow from the accepted threads: producers first, every thread bound "
                               "({{sN.response.field}}), DELETEs last.",
                               lambda c, a: labflow.auto(c, a["project"], a.get("name", ""), a.get("include_unlinked", False)),
                               {"project": P, "name": S, "include_unlinked": B}, ["project"], "write"),
    "apilab_workflow_save": _t("Create/replace a workflow: {id, name, think_time_ms, steps: [{id, api, bind: {request_field: "
                               "'{{s1.response.id}}' | '{{pool:api.field}}' | literal}, expect: {status: [200,201]}}]}. "
                               "Validated: APIs and fields must exist, bindings may only use earlier steps.",
                               lambda c, a: lab.workflow_save(c, a["project"], a["workflow"]),
                               {"project": P, "workflow": {"type": "object"}}, ["project", "workflow"], "write"),
    "apilab_workflows": _t("Workflows of a project (or one in full with id).",
                           lambda c, a: labstore.workflow(c, a["project"], a["id"]) if a.get("id") else labstore.workflows(c, a["project"]),
                           {"project": P, "id": S}, ["project"]),
    "apilab_workflow_run": _t("Execute a workflow against the project's base_url (iterations, default 1): fresh fake data per "
                              "iteration, threads resolved from earlier responses, every call stored in runs/; real ids are captured "
                              "into the pool. Returns per-step results and the first failures with request/response.",
                              lambda c, a: _run_summary(labflow.run(c, a["project"], a["workflow"], int(a.get("iterations", 1)))),
                              {"project": P, "workflow": S, "iterations": I}, ["project", "workflow"], "write"),
    "apilab_runs": _t("Recent workflow runs, or one run in full (run_id).",
                      lambda c, a: labstore.run(c, a["project"], a["run_id"]) if a.get("run_id") else labstore.runs(c, a["project"]),
                      {"project": P, "run_id": S}, ["project"]),
    "apilab_export_k6": _t("Turn a workflow into a k6 load test (threads resolved per virtual-user iteration, payload rows from the "
                           "generator + captured pool) and register it; run it with loadtest_run (JFR + live metrics).",
                           lambda c, a: labflow.export_k6(c, a["project"], a["workflow"], a.get("name", ""), a.get("profile", "load"),
                                                          int(a.get("vus", 10)), a.get("duration", "1m"), int(a.get("rows", 200))),
                           {"project": P, "workflow": S, "name": S, "profile": S, "vus": I, "duration": S, "rows": I},
                           ["project", "workflow"], "write"),
    "apilab_java_suite": _t("Also generate the repository's full Java k6 suite (scripts/loadtest.sh: JPA entities, DB-backed real "
                            "values, journeys) into the project folder. Needs JDK 25; takes minutes on first use.",
                            lambda c, a: lab.java_suite(c, a["project"]), {"project": P}, ["project"], "write"),
    # --- configuration
    "config_set": _t("Create or replace a configuration entry. section=services: {kind jar|war|command, repo, artifact (glob), port, "
                     "base_url, health {url}|{port}, env, args, java_opts, depends_on, context (war), cmd/cwd (command), jfr "
                     "{packages}}; section=repos: {build_cmd, artifact_globs, java_home, env, in_place}; section=stacks: {services: [...]}.",
                     lambda c, a: agentops.config_set(c, a["section"], a["name"], a["spec"]),
                     {"section": {"type": "string", "enum": ["services", "repos", "stacks"]}, "name": S, "spec": {"type": "object"}},
                     ["section", "name", "spec"], "write"),
    "config_remove": _t("Delete services.<name>, repos.<name>, stacks.<name> or loadtests.<name> from the configuration.",
                        lambda c, a: agentops.config_remove(c, a["section"], a["name"]),
                        {"section": {"type": "string", "enum": ["services", "repos", "stacks", "loadtests"]}, "name": S},
                        ["section", "name"], "destroy"),
    "config_set_workspace": _t("Point devctl at the folder that holds the git repositories.",
                               lambda c, a: agentops.config_set_workspace(c, a["workspace"]), {"workspace": S}, ["workspace"], "write"),
}


def schema(name: str) -> dict:
    t = TOOLS[name]
    return {"type": "object", "properties": t["props"], "required": t["required"]}


def call(name: str, args: dict) -> tuple:
    """Run a tool; returns (text, is_error). Never raises."""
    if name not in TOOLS:
        return f"unknown tool {name}", True
    try:
        out = TOOLS[name]["fn"](config.load(), args or {})
        return (out if isinstance(out, str) else json.dumps(out, indent=1, default=str)), False
    except KeyError as e:
        return f"missing argument {e}", True
    except Exception as e:
        return f"{type(e).__name__}: {e}", True
