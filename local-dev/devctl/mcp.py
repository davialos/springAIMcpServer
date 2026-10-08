"""Stdio MCP server (JSON-RPC 2.0, newline-delimited) so Claude / Cursor / other agents can drive local builds & deploys.

Registered in an agent as:  command "python3", args ["/path/to/local-dev/bin/devctl-mcp"]  (see README).
"""
from __future__ import annotations

import json
import sys

from . import __version__, builds, config, ops, pc, repos

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
    "loadtest_control": (_t("start | stop a configured k6 load test (metrics -> Prometheus, tag testid=<name>; view in Grafana).",
                            {"name": S, "action": {"enum": ["start", "stop"]}}, ["name", "action"]),
                         lambda c, a: ops.loadtest_control(c, a["name"], a["action"])),
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
