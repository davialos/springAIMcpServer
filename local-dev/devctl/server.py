"""Dashboard HTTP server (127.0.0.1 only). JSON API + one static page.

Defences, since this process can run build commands: loopback bind, Host-header allow-list (DNS rebinding),
and a required ``X-Devctl: 1`` header on every mutating request (forces a CORS preflight we never grant).
"""
from __future__ import annotations

import json
import re
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import builds, config, jfr, jvm, loadgen, loadrun, metrics, ops, pc, repos

COLLECTOR = metrics.Collector()

STATIC = Path(__file__).parent / "static"


def make_handler(port: int):
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class H(BaseHTTPRequestHandler):
        server_version = "devctl"

        def log_message(self, *a):  # quiet
            pass

        def _send(self, code, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _guard(self) -> bool:
            if self.headers.get("Host", "") not in allowed_hosts:
                self._send(403, {"error": "bad host"})
                return False
            return True

        def do_GET(self):
            if not self._guard():
                return
            u = urllib.parse.urlparse(self.path)
            if u.path in ("/", "/index.html"):
                return self._send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")
            if u.path.startswith("/static/"):
                name = u.path[len("/static/"):]
                f = STATIC / name
                if "/" in name or not name.endswith(".js") or not f.exists():
                    return self._send(404, {"error": "not found"})
                return self._send(200, f.read_bytes(), "text/javascript; charset=utf-8")
            if u.path == "/files/jfr":  # analyzer report files (self-contained HTML, JSON, XLSX)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                try:
                    data, ctype = jfr.report_file(config.load(), q.get("id", ""), q.get("f", "report.html"))
                except ValueError as e:
                    return self._send(404, {"error": str(e)})
                return self._send(200, data, ctype)
            self._dispatch("GET", u)

        def do_POST(self):
            self._mutating("POST")

        def do_PUT(self):
            self._mutating("PUT")

        def _mutating(self, method):
            if not self._guard():
                return
            if self.headers.get("X-Devctl") != "1":
                return self._send(403, {"error": "missing X-Devctl header"})
            self._dispatch(method, urllib.parse.urlparse(self.path))

        def _dispatch(self, method, u):
            q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            body = {}
            if method != "GET":
                n = int(self.headers.get("Content-Length") or 0)
                if n > 1_000_000:
                    return self._send(413, {"error": "too large"})
                body = json.loads(self.rfile.read(n) or b"{}")
            try:
                self._send(200, route(method, u.path, q, body))
            except (ValueError, pc.PcError, RuntimeError) as e:
                self._send(400, {"error": str(e)})
            except KeyError as e:
                self._send(404, {"error": f"not found: {e}"})
            except Exception as e:  # keep the dashboard alive
                self._send(500, {"error": f"{type(e).__name__}: {e}"})

    return H


def route(method: str, path: str, q: dict, body: dict):
    cfg = config.load()
    seg = [s for s in path.split("/") if s]
    if seg[:1] != ["api"]:
        raise KeyError(path)
    seg = seg[1:]
    if method == "GET":
        if seg == ["state"]:
            return ops.status(cfg)
        if seg == ["repos"]:
            return repos.discover(cfg)
        if len(seg) == 3 and seg[0] == "repos" and seg[2] == "branches":
            return repos.branches(cfg, seg[1], fetch=q.get("fetch") == "1")
        if seg == ["builds"]:
            return builds.list_builds(cfg, int(q.get("limit", 30)), q.get("repo", ""), q.get("ok") == "1")
        if len(seg) == 3 and seg[0] == "builds" and seg[2] == "log":
            return builds.read_log(cfg, seg[1], int(q.get("offset", 0)))
        if len(seg) == 2 and seg[0] == "builds":
            return builds.read_meta(cfg, seg[1])
        if len(seg) == 2 and seg[0] == "logs":
            return {"text": pc.logs(cfg, seg[1], int(q.get("n", 200)))}
        if seg == ["config"]:
            return cfg
        if len(seg) == 2 and seg[0] == "metrics":
            if seg[1] not in cfg["services"]:
                raise ValueError(f"unknown service {seg[1]}")
            COLLECTOR.interval = float(cfg["metrics"].get("interval_seconds", 2))
            COLLECTOR.ensure_started()
            return COLLECTOR.get(seg[1], float(q.get("since", 0)))
        if len(seg) == 2 and seg[0] == "readiness":
            return metrics.readiness(cfg, seg[1])
        if seg == ["jfr"]:
            return jfr.list_recordings(cfg, q.get("service", ""))
        if seg == ["jfr", "jvms"]:
            return jvm.list_jvms(cfg)
        if seg == ["jfr", "summary"]:
            return jfr.summary(cfg, q["id"])
        if seg == ["loadtests"]:
            return [{"name": n, **t} for n, t in cfg.get("loadtests", {}).items()]
        if seg == ["loadtests", "discover"]:
            return loadgen.discover(cfg, q.get("service", ""), q.get("base_url", ""))
        if seg == ["loadruns"]:
            return loadrun.list_runs(cfg, int(q.get("limit", 30)))
        if len(seg) == 2 and seg[0] == "loadruns":
            return loadrun.read(cfg, seg[1])
        if len(seg) == 3 and seg[0] == "loadruns" and seg[2] == "timeline":
            return loadrun.timeline(cfg, seg[1])
        if len(seg) == 3 and seg[0] == "loadruns" and seg[2] == "log":
            return loadrun.log(cfg, seg[1], int(q.get("offset", 0)))
        if seg == ["doctor"]:
            return [{"check": c, "ok": ok, "hint": h} for c, ok, h in ops.doctor(cfg)]
    else:
        if seg == ["builds"]:
            return builds.start(cfg, body["repo"], body["branch"], body.get("fetch", True))
        if seg == ["deploy"]:
            return ops.deploy(cfg, body["service"], body["build_id"]) if body.get("build_id") \
                else ops.deploy_latest(cfg, body["service"], body.get("branch", ""))
        if seg == ["undeploy"]:
            return ops.undeploy(cfg, body["service"])
        if len(seg) == 3 and seg[0] == "services":
            return {"result": ops.service_control(cfg, seg[1], seg[2])}
        if len(seg) == 3 and seg[0] == "stacks":
            return {"result": ops.stack_control(cfg, seg[1], seg[2])}
        if seg == ["loadtests"]:
            b = dict(body)
            return loadgen.create(cfg, b.pop("name"), b.pop("service", ""), b.pop("endpoints"), **b)
        if len(seg) == 3 and seg[0] == "loadtests" and seg[2] == "run":
            return loadrun.start(cfg, seg[1], int(body.get("vus") or 0), body.get("duration") or "")
        if len(seg) == 3 and seg[0] == "loadruns" and seg[2] == "stop":
            return loadrun.stop(cfg, seg[1])
        if seg == ["jfr", "snapshot"]:
            return jfr.snapshot(cfg, body.get("service", ""), int(body.get("pid") or 0))
        if seg == ["jfr", "record"]:
            return jfr.record(cfg, int(body.get("seconds", 60)), body.get("service", ""), int(body.get("pid") or 0))
        if seg == ["jfr", "analyze"]:
            return jfr.analyze(cfg, body["id"])
        if len(seg) == 3 and seg[0] == "infra":
            return {"result": ops.infra_control(cfg, seg[1], seg[2])}
        if seg == ["project", "sync"]:
            return {"result": pc.sync(cfg)}
        if seg == ["project", "down"]:
            pc.down(cfg)
            return {"result": "down"}
        if method == "PUT" and seg == ["config"]:
            merged = {**cfg, **body}
            config.save(merged)
            return {"saved": True}
    raise KeyError(path)


def serve(port: int) -> None:
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(port))
    print(f"devctl dashboard: http://127.0.0.1:{port}  (Ctrl-C to stop)", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
