import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


def sh(*a, cwd=None):
    subprocess.run(a, cwd=cwd, check=True, capture_output=True)


class DevctlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        os.environ["LOCALDEV_HOME"] = str(self.tmp / "home")
        for m in [m for m in sys.modules if m.startswith("devctl")]:
            del sys.modules[m]
        from devctl import config
        self.config = config
        ws = self.tmp / "ws"
        repo = ws / "billing"
        repo.mkdir(parents=True)
        sh("git", "init", "-q", "-b", "main", cwd=repo)
        (repo / "pom.xml").write_text("<project/>")
        sh("git", "-c", "user.email=a@b", "-c", "user.name=t", "add", ".", cwd=repo)
        sh("git", "-c", "user.email=a@b", "-c", "user.name=t", "commit", "-qm", "init", cwd=repo)
        sh("git", "branch", "feature/x", cwd=repo)
        jb = self.tmp / "jboss"
        (jb / "bin").mkdir(parents=True)
        (jb / "bin" / "standalone.sh").write_text("#!/bin/sh\n")
        (jb / "standalone" / "configuration").mkdir(parents=True)
        cfg = config.load()
        cfg.update({"workspace": str(ws), "builds_dir": str(self.tmp / "b"), "java_home": "/jdk",
                    "jboss": {**cfg["jboss"], "home": str(jb), "java_home": "/jdk17"},
                    "repos": {"billing": {"build_cmd": "mkdir -p target && echo core>target/core-1.war && echo web>target/web-1.war && echo j>target/api-1.jar",
                                          "artifact_globs": ["**/target/*.war", "**/target/*.jar"]}},
                    "services": {"core": {"kind": "war", "repo": "billing", "artifact": "core-*.war", "context": "core"},
                                 "web": {"kind": "war", "repo": "billing", "artifact": "web-*.war", "context": "web"},
                                 "api": {"kind": "jar", "repo": "billing", "artifact": "api-*.jar", "port": 8081, "depends_on": ["postgres"]}},
                    "stacks": {"s": ["core", "web"]}})
        config.save(cfg)
        self.cfg = config.load()

    def test_discover_and_branches(self):
        from devctl import repos
        r = repos.discover(self.cfg)
        self.assertEqual([x["name"] for x in r], ["billing"])
        self.assertEqual(r[0]["build_system"], "maven")
        self.assertIn("feature/x", repos.branches(self.cfg, "billing"))
        with self.assertRaises(ValueError):
            repos.repo_path(self.cfg, "../etc")

    def _build(self, branch):
        from devctl import builds
        m = builds.start(self.cfg, "billing", branch, fetch=False)
        for _ in range(100):
            m = builds.read_meta(self.cfg, m["id"])
            if m["status"] != "running":
                break
            time.sleep(0.1)
        return m

    def test_build_and_multiwar_deploy(self):
        from devctl import builds, ops, compose, pc
        m = self._build("feature/x")
        self.assertEqual(m["status"], "success", m)
        self.assertEqual(sorted(m["artifacts"]), ["api-1.jar", "core-1.war", "web-1.war"])
        self.assertIn("$ mkdir", builds.read_log(self.cfg, m["id"])["text"])
        pc.available = lambda: False  # no process-compose in CI
        ops.deploy(self.cfg, "core", m["id"])
        ops.deploy(self.cfg, "web", m["id"])
        dep = compose.jboss_base(self.cfg) / "deployments"
        self.assertEqual(sorted(p.name for p in dep.iterdir()), ["core.war", "web.war"])
        proj = compose.build_project(self.cfg, self.config.state())
        jb = proj["processes"]["jboss-eap"]
        self.assertIn("standalone.sh", jb["command"])
        self.assertIn("JAVA_HOME=/jdk17", jb["environment"])
        self.assertTrue(proj["processes"]["api"]["disabled"])  # jar not deployed yet
        self.assertEqual(proj["processes"]["grafana"]["namespace"], "infra")
        ops.deploy(self.cfg, "api", m["id"])
        proj = compose.build_project(self.cfg, self.config.state())
        self.assertNotIn("disabled", proj["processes"]["api"])
        self.assertIn("SERVER_PORT=8081", proj["processes"]["api"]["environment"])
        self.assertNotIn("depends_on", proj["processes"]["api"])  # postgres disabled -> no dangling dependency
        ops.undeploy(self.cfg, "web")
        self.assertEqual([p.name for p in dep.iterdir()], ["core.war"])
        with self.assertRaises(ValueError):
            ops.deploy(self.cfg, "core", "nope")

    def test_failed_build_reported(self):
        from devctl import builds
        cfg = self.config.load()
        cfg["repos"]["billing"]["build_cmd"] = "exit 3"
        self.config.save(cfg)
        self.cfg = self.config.load()
        m = self._build("main")
        self.assertEqual(m["status"], "failed")
        self.assertIn("exited with 3", m["error"])

    def test_rejects_option_like_branch(self):
        from devctl import builds
        with self.assertRaises(ValueError):
            builds.start(self.cfg, "billing", "--upload-pack=x")

    def test_k6_and_log_shipping(self):
        import threading
        from http.server import BaseHTTPRequestHandler, HTTPServer
        from devctl import compose, shipper
        cfg = self.config.load()
        cfg["loadtests"] = {"smoke": {"script": "smoke.js", "vus": 5, "duration": "30s", "env": {"BASE_URL": "http://x"}}}
        proj = compose.build_project(cfg, self.config.state())
        k6 = proj["processes"]["k6-smoke"]
        self.assertTrue(k6["disabled"])
        for part in ("k6 run", "experimental-prometheus-rw", "--vus 5", "--duration 30s", "BASE_URL=http://x", "smoke.js"):
            self.assertIn(part, k6["command"])
        self.assertIn("web.enable-remote-write-receiver", proj["processes"]["prometheus"]["command"])
        self.assertEqual(proj["processes"]["log-shipper"]["depends_on"], {"loki": {"condition": "process_healthy"}})
        self.assertTrue(proj["processes"]["grafana"]["log_location"].endswith("logs/grafana.log"))

        got = []

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                got.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(204); self.end_headers()
            def log_message(self, *a): pass
        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            compose.log_dir().mkdir(parents=True)
            f = compose.log_dir() / "orders-api.log"
            f.write_text("old line\n")
            sh_ = shipper.Shipper(cfg, f"http://127.0.0.1:{srv.server_address[1]}")
            self.assertEqual(sh_.poll(), 0)  # history at startup is not replayed
            with open(f, "a") as fh:
                fh.write("\x1b[32mhello\x1b[0m\nsecond\npart")
            self.assertEqual(sh_.poll(), 2)  # incomplete trailing line is held back
            self.assertTrue(sh_.flush())
            st = got[0]["streams"][0]
            self.assertEqual(st["stream"]["service"], "orders-api")
            self.assertEqual([v[1] for v in st["values"]], ["hello", "second"])
            with open(f, "a") as fh:
                fh.write("ial\n")
            sh_.poll()
            sh_.flush()
            self.assertEqual(got[1]["streams"][0]["values"][0][1], "partial")
        finally:
            srv.shutdown()
        down = shipper.Shipper(cfg, "http://127.0.0.1:1")
        down.pending[(("service", "x"),)] = [(1, "l")]
        self.assertFalse(down.flush())
        self.assertEqual(len(down.pending), 1)  # retained for retry

    def test_mcp(self):
        from devctl import mcp
        r = mcp.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        names = [t["name"] for t in r["result"]["tools"]]
        self.assertIn("build", names)
        r = mcp.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "list_repos", "arguments": {}}})
        self.assertIn("billing", r["result"]["content"][0]["text"])
        r = mcp.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "build_status", "arguments": {"build_id": "x"}}})
        self.assertTrue(r["result"]["isError"])
        self.assertIsNone(mcp.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))

    def test_dashboard_guards(self):
        import threading, urllib.request, urllib.error
        from http.server import ThreadingHTTPServer
        from devctl import server
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(0))
        port = srv.server_address[1]
        srv.RequestHandlerClass = server.make_handler(port)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            self.assertIn(b"Local Dev Control", urllib.request.urlopen(f"http://127.0.0.1:{port}/").read())
            self.assertEqual(json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/api/repos"))[0]["name"], "billing")
            req = urllib.request.Request(f"http://127.0.0.1:{port}/api/builds", data=b"{}", method="POST")
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(req)
            self.assertEqual(e.exception.code, 403)  # no X-Devctl header
            bad = urllib.request.Request(f"http://127.0.0.1:{port}/api/repos", headers={"Host": "evil.example"})
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(bad)
            self.assertEqual(e.exception.code, 403)  # DNS-rebinding guard
        finally:
            srv.shutdown()


if __name__ == "__main__":
    unittest.main()
