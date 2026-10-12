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

from . import agents, assistant, builds, config, jfr, jvm, loadgen, loadrun, mcp, metrics, ops, pc, repos

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
            if u.path == "/mcp":  # no server-initiated stream in stateless mode
                self.send_response(405)
                self.send_header("Allow", "POST")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if u.path in ("/lab", "/lab/"):
                return self._send(200, (STATIC / "lab.html").read_bytes(), "text/html; charset=utf-8")
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
            if urllib.parse.urlparse(self.path).path == "/mcp":
                return self._mcp()
            self._mutating("POST")

        def _mcp(self):
            """MCP Streamable HTTP, stateless: one JSON-RPC message (or batch) per POST, JSON response."""
            if not self._guard():
                return
            origin = self.headers.get("Origin")
            if origin and origin.split("://", 1)[-1] not in allowed_hosts:
                return self._send(403, {"error": "origin not allowed"})
            token = (config.load().get("mcp") or {}).get("http_token")
            if token and self.headers.get("Authorization", "") != f"Bearer {token}":
                return self._send(401, {"error": "bearer token required (mcp.http_token)"})
            n = int(self.headers.get("Content-Length") or 0)
            if n > 4_000_000:
                return self._send(413, {"error": "too large"})
            try:
                msg = json.loads(self.rfile.read(n) or b"null")
            except ValueError as e:
                return self._send(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": str(e)}})
            resp = mcp.handle(msg)
            if resp is None:  # notifications / responses only
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self._send(200, resp)

        def do_DELETE(self):
            if urllib.parse.urlparse(self.path).path == "/mcp":  # stateless server: nothing to terminate
                self.send_response(405)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self._send(404, {"error": "not found"})

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
    if seg[:1] == ["lab"]:
        return lab_route(cfg, method, seg[1:], q, body)
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
        if seg == ["assistant", "status"]:
            return assistant.status(cfg)
        if seg == ["agents"]:
            return {"mcp_bin": str(agents.MCP_BIN), "http_url": agents.http_url(cfg),
                    "snippets": {k: {**v, "rendered": agents.render(v)} for k, v in agents.snippets(cfg).items()}}
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
        if seg == ["assistant", "chat"]:
            return assistant.chat(cfg, body.get("session"), body.get("message", ""))
        if seg == ["assistant", "decide"]:
            return assistant.decide(cfg, body["session"], bool(body.get("approve")))
        if seg == ["assistant", "reset"]:
            assistant.reset(body.get("session", ""))
            return {"reset": True}
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


def lab_route(cfg: dict, method: str, seg: list, q: dict, body: dict):
    from .lab import flow, service as lab, store
    if seg == ["projects"]:
        if method == "GET":
            return lab.projects(cfg)
        return lab.create(cfg, body["name"], body.get("repo", ""), body.get("service", ""), body.get("base_url", ""), body.get("headers"))
    if not seg:
        raise KeyError("lab")
    p, rest = seg[0], seg[1:]
    if method == "GET":
        if rest == []:
            return {**store.project(cfg, p), "pool": {k: len(v) for k, v in store.pool(cfg, p).items()}}
        if rest == ["apis"]:
            return lab.api_list(cfg, p)
        if rest == ["graph"]:  # everything the threads view needs in one call
            return {"apis": store.apis(cfg, p), "links": store.links(cfg, p)}
        if len(rest) == 2 and rest[0] == "apis":
            return lab.api_detail(cfg, p, rest[1])
        if rest == ["links"]:
            return lab.links_view(cfg, p, q.get("status", ""), q.get("api", ""), float(q.get("min", 0)))
        if len(rest) == 2 and rest[0] == "data":
            return lab.data_view(cfg, p, rest[1], int(q.get("limit", 100)))
        if rest == ["workflows"]:
            return store.workflows(cfg, p)
        if len(rest) == 2 and rest[0] == "workflows":
            return store.workflow(cfg, p, rest[1])
        if rest == ["runs"]:
            return store.runs(cfg, p, int(q.get("limit", 30)))
        if len(rest) == 2 and rest[0] == "runs":
            return store.run(cfg, p, rest[1])
        if rest == ["pool"]:
            return store.pool(cfg, p)
    else:
        if rest == ["discover"]:
            return lab.run_discovery(cfg, p, body.get("source", "auto"))
        if rest == ["links"]:
            return lab.link_set(cfg, p, body["from_api"], body["from_field"], body["to_api"], body["to_field"],
                                body.get("status", "accepted"), body.get("reason", ""), "user")
        if rest == ["links", "status"]:
            return lab.link_status(cfg, p, body["ids"], body["status"])
        if len(rest) == 2 and rest[0] == "rules":
            return lab.set_rules(cfg, p, rest[1], body.get("rules", {}), bool(body.get("replace")))
        if rest == ["generate"]:
            return lab.generate(cfg, p, body.get("api", ""), int(body.get("count", 20)))
        if rest == ["workflows"]:
            return lab.workflow_save(cfg, p, body)
        if rest == ["workflows", "auto"]:
            return flow.auto(cfg, p, body.get("name", ""), bool(body.get("include_unlinked")))
        if len(rest) == 3 and rest[0] == "workflows" and rest[2] == "run":
            r = flow.run(cfg, p, rest[1], int(body.get("iterations", 1)))
            return {k: r[k] for k in ("id", "ok", "failed", "summary")}
        if len(rest) == 3 and rest[0] == "workflows" and rest[2] == "k6":
            return flow.export_k6(cfg, p, rest[1], body.get("name", ""), body.get("profile", "load"), int(body.get("vus", 10)),
                                  body.get("duration", "1m"), int(body.get("rows", 200)))
        if rest == ["java-suite"]:
            return lab.java_suite(cfg, p)
    raise KeyError("/".join(seg))


def serve(port: int) -> None:
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(port))
    print(f"devctl dashboard: http://127.0.0.1:{port}   MCP (HTTP): http://127.0.0.1:{port}/mcp   (Ctrl-C to stop)", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
