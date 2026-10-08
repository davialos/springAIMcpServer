"""MCP server for devctl - lets Claude Code, Cursor, Antigravity, VS Code, Windsurf, Codex, Gemini CLI ... drive local
builds, deploys, logs, load tests and profiling.

Transports: stdio (``bin/devctl-mcp``, newline-delimited JSON-RPC) and Streamable HTTP (``POST /mcp`` on the dashboard,
stateless, JSON responses). Both call :func:`handle`. Tools come from tools.py; resources and prompts give agents
context and ready-made workflows.
"""
from __future__ import annotations

import json
import sys

from . import __version__, agentops, builds, config, loadrun, ops, tools

VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

INSTRUCTIONS = """devctl controls the developer's LOCAL machine: git worktree builds of any repo@branch, deployments (Spring Boot
jars as processes, WARs into one JBoss EAP), process-compose processes, logs, live service metrics, k6 load tests and
Java Flight Recorder profiling with an analyzer that names the hot code lines.

How to work with it:
- Orient first: `status` (all services) or `diagnose <service>` (one service, everything relevant). Do not guess names -
  `list_repos`, `list_branches`, `config_get` give the real ones.
- To run a branch: `ship repo branch service` (build -> deploy -> wait healthy) instead of chaining calls yourself.
  After `build` use `wait_build`; after `deploy`/start use `wait_healthy`.
- Failures: read `log_tail` / `recent_errors`, then `search_logs` with a regex; report the root cause with the log line.
- Performance: `perf_snapshot` for now, `loadtest_discover` -> `loadtest_create` -> `loadtest_run` -> `loadtest_result`
  for a load test; the result's JFR verdict and `jfr_summary` give file:line hot spots - quote them.
- New project: `config_set_workspace`, then `config_set` section=repos/services (read the repo's build files first to fill
  artifact globs, ports and health URLs).
- Tools marked destructive (undeploy, config_remove) change things the developer may care about - say what you will do first.
Everything is local; nothing here deploys to shared environments."""

RESOURCES = {
    "devctl://status": ("Status of all services", "application/json", lambda c: ops.status(c)),
    "devctl://config": ("devctl configuration (services, repos, stacks, load tests)", "application/json",
                        lambda c: {k: c[k] for k in ("workspace", "services", "repos", "stacks", "loadtests", "jfr", "infra")}),
    "devctl://builds/recent": ("Recent builds", "application/json", lambda c: builds.list_builds(c, 20)),
    "devctl://loadruns/recent": ("Recent load-test runs", "application/json", lambda c: loadrun.list_runs(c, 20)),
    "devctl://guide": ("How to use the devctl tools", "text/markdown", lambda c: INSTRUCTIONS),
}

PROMPTS = {
    "ship-branch": ("Build a branch, deploy it locally and confirm it is healthy",
                    [("repo", True), ("branch", True), ("service", True)],
                    "Run {branch} of {repo} locally as {service}: call `ship`, then if it fails find the cause with "
                    "`search_logs`/`diagnose` and tell me the exact error line and a fix. If it succeeds, give me the URL and "
                    "a `perf_snapshot`."),
    "investigate-service": ("Find out why a local service misbehaves", [("service", True)],
                            "Investigate {service}: start with `diagnose`, then dig into logs (`search_logs` for exceptions) and, if it "
                            "is slow, `perf_snapshot` and the latest JFR analysis. Finish with root cause, evidence and the fix."),
    "performance-check": ("Load test a service and explain the bottleneck", [("service", True), ("vus", False), ("duration", False)],
                          "Load test {service}: check `perf_readiness`, discover endpoints, create a 'load' test with the GET "
                          "endpoints ({vus} VUs, {duration} hold), run it, wait for the result, then explain throughput, p95, errors "
                          "and the JFR hot spots with file:line and what to change."),
    "onboard-project": ("Register a repository of the workspace as a local service", [("repo", True)],
                        "Onboard {repo}: read its build files (pom.xml/build.gradle/package.json) and application config to find the "
                        "artifact, port, context path and health URL; then `config_set` the repo and service, `ship` its default "
                        "branch and confirm it is healthy. Explain what you configured."),
}


def _ok(id_, result):
    return {"jsonrpc": "2.0", "id": id_, "result": result}


def _err(id_, code, msg):
    return {"jsonrpc": "2.0", "id": id_, "error": {"code": code, "message": msg}}


def _annotations(effect: str) -> dict:
    return {"readOnlyHint": effect == "read", "destructiveHint": effect == "destroy",
            "idempotentHint": effect == "read", "openWorldHint": False}


def handle(req):
    """One JSON-RPC message (or a batch list) in; the response (or None for notifications) out."""
    if isinstance(req, list):
        out = [r for r in (handle(x) for x in req) if r is not None]
        return out or None
    if not isinstance(req, dict):
        return _err(None, -32600, "invalid request")
    m, id_, p = req.get("method"), req.get("id"), req.get("params") or {}
    if id_ is None:
        return None  # notification (initialized, cancelled, ...)
    try:
        if m == "initialize":
            v = p.get("protocolVersion")
            return _ok(id_, {"protocolVersion": v if v in VERSIONS else VERSIONS[0],
                             "capabilities": {"tools": {"listChanged": False}, "resources": {"listChanged": False},
                                              "prompts": {"listChanged": False}},
                             "serverInfo": {"name": "devctl", "title": "devctl - local dev control", "version": __version__},
                             "instructions": INSTRUCTIONS})
        if m == "ping":
            return _ok(id_, {})
        if m == "tools/list":
            return _ok(id_, {"tools": [{"name": n, "description": t["description"], "inputSchema": tools.schema(n),
                                        "annotations": _annotations(t["effect"])} for n, t in tools.TOOLS.items()]})
        if m == "tools/call":
            text, is_err = tools.call(p.get("name", ""), p.get("arguments") or {})
            if p.get("name") not in tools.TOOLS:
                return _err(id_, -32602, text)
            return _ok(id_, {"content": [{"type": "text", "text": text}], "isError": is_err})
        if m == "resources/list":
            return _ok(id_, {"resources": [{"uri": u, "name": u.split("//")[1], "description": d, "mimeType": mt}
                                           for u, (d, mt, _) in RESOURCES.items()]})
        if m == "resources/templates/list":
            return _ok(id_, {"resourceTemplates": [{"uriTemplate": "devctl://logs/{name}", "name": "logs",
                                                     "description": "Last 200 log lines of a process", "mimeType": "text/plain"}]})
        if m == "resources/read":
            uri, cfg = p.get("uri", ""), config.load()
            if uri.startswith("devctl://logs/"):
                text, mt = agentops.read_logs(cfg, uri[len("devctl://logs/"):], 200), "text/plain"
            elif uri in RESOURCES:
                _, mt, fn = RESOURCES[uri]
                v = fn(cfg)
                text = v if isinstance(v, str) else json.dumps(v, indent=1, default=str)
            else:
                return _err(id_, -32002, f"resource not found: {uri}")
            return _ok(id_, {"contents": [{"uri": uri, "mimeType": mt, "text": text}]})
        if m == "prompts/list":
            return _ok(id_, {"prompts": [{"name": n, "description": d, "arguments": [{"name": a, "required": r} for a, r in args]}
                                         for n, (d, args, _) in PROMPTS.items()]})
        if m == "prompts/get":
            n = p.get("name")
            if n not in PROMPTS:
                return _err(id_, -32602, f"unknown prompt {n}")
            d, args, tpl = PROMPTS[n]
            vals = {"vus": "10", "duration": "1m", **(p.get("arguments") or {})}
            missing = [a for a, r in args if r and not vals.get(a)]
            if missing:
                return _err(id_, -32602, f"missing arguments: {missing}")
            return _ok(id_, {"description": d, "messages": [{"role": "user", "content": {"type": "text", "text": tpl.format(**vals)}}]})
        return _err(id_, -32601, f"method not found: {m}")
    except Exception as e:  # never break the transport
        return _err(id_, -32603, f"{type(e).__name__}: {e}")


def serve() -> None:
    """stdio transport. stdout carries protocol messages only; anything else printed goes to stderr."""
    proto, sys.stdout = sys.stdout, sys.stderr
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            resp = handle(json.loads(line))
        except ValueError as e:
            resp = _err(None, -32700, f"parse error: {e}")
        if resp is not None:
            proto.write(json.dumps(resp) + "\n")
            proto.flush()
