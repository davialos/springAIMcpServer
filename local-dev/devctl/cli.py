"""devctl command line."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time

from . import builds, config, ops, pc, repos


def _p(o):
    print(o if isinstance(o, str) else json.dumps(o, indent=2, default=str))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="devctl", description="Local development control plane (process-compose).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add(name, help_, *args):
        sp = sub.add_parser(name, help=help_)
        for a in args:
            sp.add_argument(a) if not a.startswith("-") else sp.add_argument(a, action="store_true")
        return sp

    add("init", "write a starter config to ~/.localdev/config.json")
    add("doctor", "check prerequisites")
    add("repos", "list repositories in the workspace")
    add("branches", "list branches of a repo", "repo", "--fetch")
    b = add("build", "build repo@branch locally", "repo", "branch", "--wait")
    add("builds", "list recent builds")
    add("log", "print a build log", "build_id")
    d = add("deploy", "deploy a build to a service (default: newest successful build)", "service")
    d.add_argument("--build"); d.add_argument("--branch")
    add("undeploy", "undeploy a service", "service")
    add("status", "services, health, infra")
    for a in ("start", "stop", "restart"):
        add(a, f"{a} a service", "service")
    add("stack", "up|down a stack", "action", "name")
    add("infra", "start|stop|restart prometheus|loki|grafana|postgres", "action", "name")
    add("loadtest", "start|stop a k6 load test", "action", "name")
    add("ship-logs", "run the Loki log shipper (normally managed by process-compose)")
    add("logs", "process logs", "name")
    add("up", "(re)generate the project and start/update process-compose headless")
    add("down", "stop everything")
    add("tui", "attach the process-compose TUI")
    add("dashboard", "run the web dashboard")
    add("mcp", "run the stdio MCP server")
    add("_run-build", "internal", "build_id")
    a = ap.parse_args(argv)
    cfg = config.load()
    try:
        return _dispatch(a, cfg)
    except (ValueError, RuntimeError, pc.PcError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


def _dispatch(a, cfg) -> int:
    c = a.cmd
    if c == "init":
        if config.CONFIG_FILE.exists():
            print(f"{config.CONFIG_FILE} exists; not overwriting"); return 1
        ex = os.path.join(os.path.dirname(__file__), "..", "config.example.json")
        config.HOME.mkdir(parents=True, exist_ok=True)
        shutil.copy(ex, config.CONFIG_FILE)
        print(f"wrote {config.CONFIG_FILE} - edit workspace/jboss/services, then `devctl doctor`")
    elif c == "doctor":
        for name, ok, hint in ops.doctor(cfg):
            print(f"[{'ok' if ok else '!!'}] {name}" + ("" if ok else f"   -> {hint}"))
    elif c == "repos":
        for r in repos.discover(cfg):
            print(f"{r['name']:30} {r['build_system']:8} on {r['current_branch']}")
    elif c == "branches":
        print("\n".join(repos.branches(cfg, a.repo, a.fetch)))
    elif c == "build":
        m = builds.start(cfg, a.repo, a.branch)
        print(m["id"])
        if a.wait:
            return _follow(cfg, m["id"])
    elif c == "builds":
        for m in builds.list_builds(cfg):
            print(f"{m['id']:60} {m['status']:8} {m['commit'] or '-':9} {','.join(m['artifacts'])}")
    elif c == "log":
        _p(builds.read_log(cfg, a.build_id)["text"])
    elif c == "deploy":
        _p(ops.deploy(cfg, a.service, a.build) if a.build else ops.deploy_latest(cfg, a.service, a.branch or ""))
    elif c == "undeploy":
        _p(ops.undeploy(cfg, a.service))
    elif c == "status":
        _status(ops.status(cfg))
    elif c in ("start", "stop", "restart"):
        _p(ops.service_control(cfg, a.service, c))
    elif c == "stack":
        _p(ops.stack_control(cfg, a.name, a.action))
    elif c == "infra":
        _p(ops.infra_control(cfg, a.name, a.action))
    elif c == "loadtest":
        _p(ops.loadtest_control(cfg, a.name, a.action))
    elif c == "ship-logs":
        from . import shipper
        shipper.Shipper(cfg).run()
    elif c == "logs":
        _p(pc.logs(cfg, a.name))
    elif c == "up":
        _p(pc.sync(cfg))
    elif c == "down":
        pc.down(cfg)
    elif c == "tui":
        os.execvp("process-compose", ["process-compose", "attach", "-p", str(cfg["process_compose_port"])])
    elif c == "dashboard":
        from . import server
        server.serve(cfg["dashboard_port"])
    elif c == "mcp":
        from . import mcp
        mcp.serve()
    elif c == "_run-build":
        return builds.run(cfg, a.build_id)
    return 0


def _follow(cfg, build_id) -> int:
    off = 0
    while True:
        r = builds.read_log(cfg, build_id, off)
        sys.stdout.write(r["text"]); sys.stdout.flush()
        off = r["offset"]
        m = builds.read_meta(cfg, build_id)
        if m["status"] != builds.RUNNING:
            r = builds.read_log(cfg, build_id, off)
            sys.stdout.write(r["text"])
            return 0 if m["status"] == builds.SUCCESS else 1
        time.sleep(1)


def _status(s) -> None:
    print(f"process-compose: {'up' if s['process_compose']['up'] else 'down'} (port {s['process_compose']['port']})")
    for n, v in s["services"].items():
        dep = v["deployed"]
        h = v["health"] or {}
        state = (v["process"] or {}).get("status", "-")
        print(f"{n:24} {v['kind']:7} {state:10} health={h.get('detail', '-'):18} "
              f"{(dep['branch'] + '@' + str(dep['commit'])) if dep else 'not deployed'}")
    for n, v in s["loadtests"].items():
        print(f"k6/{n:21} {(v['process'] or {}).get('status', 'idle')}")
    for n, v in s["infra"].items():
        print(f"infra/{n:18} {(v['process'] or {}).get('status', 'off' if not v['enabled'] else '-')}")
