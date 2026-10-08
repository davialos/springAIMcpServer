"""Stdio MCP server (JSON-RPC 2.0, newline-delimited) so Claude / Cursor / other agents can drive local builds & deploys.

Registered in an agent as:  command "python3", args ["/path/to/local-dev/bin/devctl-mcp"]  (see README).
"""
from __future__ import annotations

import json
import sys

from . import __version__, builds, config, jfr, jvm, loadgen, loadrun, metrics, ops, pc, repos

PROTOCOL = "2025-06-18"
S = {"type": "string"}


def _t(desc, props=None, req=()):
    return {"description": desc, "inputSchema": {"type": "object", "properties": props or {}, "required": list(req)}}


TOOLS = {
    "list_repos": (_t("List git repositories found in the configured workspace folder."), lambda c, a: repos.discover(c)),
    "list_branches": (_t("List branches of a repo (optionally git fetch first).", {"repo": S, "fetch": {"type": "boolean"}}, ["repo"]),
                      lambda c, a: repos.branches(c, a["repo"], a.get("fetch", False))),
    "build": (_t("Start a local build of repo@branch in an isolated git worktree. Returns the build id; poll build_status.",
                 {"repo": S, "branch": S}, ["repo", "branch"]), lambda c, a: builds.start(c, a["repo"], a["branch"])),
    "build_status": (_t("Status of a build plus the last part of its log.", {"build_id": S, "tail_chars": {"type": "integer"}}, ["build_id"]),
                     lambda c, a: {**builds.read_meta(c, a["build_id"]),
                                   "log_tail": builds.read_log(c, a["build_id"])["text"][-int(a.get("tail_chars", 4000)):]}),
    "list_builds": (_t("Recent builds (newest first).", {"repo": S, "only_success": {"type": "boolean"}}),
                    lambda c, a: builds.list_builds(c, 20, a.get("repo", ""), a.get("only_success", False))),
    "deploy": (_t("Deploy a build's artifact to a service (jar -> process, war -> JBoss EAP deployments). Omit build_id to use "
                  "the newest successful build (optionally of a branch).", {"service": S, "build_id": S, "branch": S}, ["service"]),
               lambda c, a: ops.deploy(c, a["service"], a["build_id"]) if a.get("build_id") else ops.deploy_latest(c, a["service"], a.get("branch", ""))),
    "undeploy": (_t("Undeploy a service.", {"service": S}, ["service"]), lambda c, a: ops.undeploy(c, a["service"])),
    "status": (_t("Everything: services (deployed build, process state, health), stacks, infra, JBoss."), lambda c, a: ops.status(c)),
    "service_control": (_t("start | stop | restart a service.", {"service": S, "action": {"enum": ["start", "stop", "restart"]}}, ["service", "action"]),
                        lambda c, a: ops.service_control(c, a["service"], a["action"])),
    "stack_control": (_t("up | down a stack of services (multi-WAR apps share one JBoss).", {"stack": S, "action": {"enum": ["up", "down"]}}, ["stack", "action"]),
                      lambda c, a: ops.stack_control(c, a["stack"], a["action"])),
    "infra_control": (_t("start | stop | restart prometheus, loki, grafana or postgres.", {"name": S, "action": {"enum": ["start", "stop", "restart"]}}, ["name", "action"]),
                      lambda c, a: ops.infra_control(c, a["name"], a["action"])),
    "perf_snapshot": (_t("Live performance of a service right now (2 s window): req/s, avg/p95 latency, 5xx %, heap, CPU, "
                         "threads, GC, pool, plus the busiest endpoints. Reads /actuator/prometheus (or /actuator/metrics).",
                         {"service": S}, ["service"]), lambda c, a: metrics.snapshot(c, a["service"])),
    "perf_readiness": (_t("Which observability/load-test endpoints a service exposes and the minimal change for each missing one.",
                          {"service": S}, ["service"]), lambda c, a: metrics.readiness(c, a["service"])),
    "jfr_list_jvms": (_t("Local JVMs (pid, main class, devctl service if started by devctl)."), lambda c, a: jvm.list_jvms(c)),
    "jfr_snapshot": (_t("Dump the continuous JFR recording (last jfr.maxage) of a service or pid now; analysis starts automatically.",
                        {"service": S, "pid": {"type": "integer"}}),
                     lambda c, a: jfr.snapshot(c, a.get("service", ""), int(a.get("pid") or 0))),
    "jfr_record": (_t("Timed JFR recording of a service or any local JVM pid.",
                      {"service": S, "pid": {"type": "integer"}, "seconds": {"type": "integer"}}),
                   lambda c, a: jfr.record(c, int(a.get("seconds", 60)), a.get("service", ""), int(a.get("pid") or 0))),
    "jfr_list": (_t("JFR recordings with analysis status, health and score.", {"service": S}),
                 lambda c, a: jfr.list_recordings(c, a.get("service", ""))),
    "jfr_analyze": (_t("(Re)analyze a recording with the JFR analyzer.", {"id": S}, ["id"]), lambda c, a: jfr.analyze(c, a["id"])),
    "jfr_summary": (_t("Analysis summary of a recording: status, score, key metrics, top issues, hot spots, findings.",
                       {"id": S}, ["id"]), lambda c, a: jfr.summary(c, a["id"])),
    "loadtest_discover": (_t("Discover a service's HTTP endpoints (OpenAPI or actuator mappings).", {"service": S, "base_url": S}),
                          lambda c, a: loadgen.discover(c, a.get("service", ""), a.get("base_url", ""))),
    "loadtest_create": (_t("Generate a k6 script and register it. endpoints: [{method, path, weight?, body?}]; profile: "
                           "smoke|load|stress|spike|soak; path_values: {param: [values]}.",
                           {"name": S, "service": S, "endpoints": {"type": "array", "items": {"type": "object"}},
                            "profile": S, "vus": {"type": "integer"}, "duration": S, "path_values": {"type": "object"},
                            "p95_ms": {"type": "integer"}, "max_error_rate": {"type": "number"}},
                           ["name", "service", "endpoints"]),
                        lambda c, a: loadgen.create(c, a["name"], a["service"], a["endpoints"],
                                                    **{k: v for k, v in a.items() if k not in ("name", "service", "endpoints")})),
    "loadtest_run": (_t("Run a load test: JFR on the target, k6, live service metrics, then JFR analysis. Returns the run id.",
                        {"name": S, "vus": {"type": "integer"}, "duration": S}, ["name"]),
                     lambda c, a: loadrun.start(c, a["name"], int(a.get("vus") or 0), a.get("duration", ""))),
    "loadtest_result": (_t("Status/results of a load run: k6 figures, service-side peaks, JFR analysis.", {"run_id": S}, ["run_id"]),
                        lambda c, a: loadrun.read(c, a["run_id"])),
    "loadtest_runs": (_t("Recent load runs."), lambda c, a: loadrun.list_runs(c, 20)),
    "loadtest_stop": (_t("Stop a running load run (k6 is interrupted; JFR is still saved and analyzed).", {"run_id": S}, ["run_id"]),
                      lambda c, a: loadrun.stop(c, a["run_id"])),
    "logs": (_t("Last N log lines of a process (service, jboss-eap, grafana...).", {"name": S, "lines": {"type": "integer"}}, ["name"]),
             lambda c, a: pc.logs(c, a["name"], int(a.get("lines", 100)))),
    "sync_project": (_t("Regenerate and apply the process-compose project (starts it headless if not running)."), lambda c, a: pc.sync(c)),
}


def _reply(id_, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": id_}
    msg["error" if error else "result"] = error or result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def handle(req: dict):
    """Returns the response dict for a request, or None for notifications."""
    m, id_ = req.get("method"), req.get("id")
    if id_ is None:
        return None
    if m == "initialize":
        return {"jsonrpc": "2.0", "id": id_, "result": {"protocolVersion": PROTOCOL, "capabilities": {"tools": {}},
                                                         "serverInfo": {"name": "devctl", "version": __version__}}}
    if m == "ping":
        return {"jsonrpc": "2.0", "id": id_, "result": {}}
    if m == "tools/list":
        return {"jsonrpc": "2.0", "id": id_, "result": {"tools": [{"name": n, **spec} for n, (spec, _) in TOOLS.items()]}}
    if m == "tools/call":
        p = req.get("params", {})
        name = p.get("name")
        if name not in TOOLS:
            return {"jsonrpc": "2.0", "id": id_, "error": {"code": -32602, "message": f"unknown tool {name}"}}
        try:
            out = TOOLS[name][1](config.load(), p.get("arguments") or {})
            text, err = (out if isinstance(out, str) else json.dumps(out, indent=2, default=str)), False
        except Exception as e:
            text, err = f"{type(e).__name__}: {e}", True
        return {"jsonrpc": "2.0", "id": id_, "result": {"content": [{"type": "text", "text": text}], "isError": err}}
    return {"jsonrpc": "2.0", "id": id_, "error": {"code": -32601, "message": f"method not found: {m}"}}


def serve() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            resp = handle(json.loads(line))
        except Exception as e:
            resp = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": str(e)}}
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
