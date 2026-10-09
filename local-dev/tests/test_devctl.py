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

    def test_celfaker_is_a_builtin_service(self):
        import tempfile
        from devctl import compose, ops
        cfg = self.config.load()
        svc = cfg["services"]["celfaker"]
        self.assertEqual((svc["kind"], svc["builtin"], svc["port"]), ("command", "celfaker", 8110))
        proj = compose.build_project(cfg, self.config.state())
        p = proj["processes"]["celfaker"]
        self.assertIn("scripts/celfaker.sh serve --port 8110", p["command"])
        self.assertEqual(sum(e.startswith("JAVA_HOME=") for e in p["environment"]), 1)  # auto:25 resolves (here: falls back to the env)
        self.assertEqual(p["readiness_probe"]["http_get"]["path"], "/api/example")
        self.assertEqual(p["readiness_probe"]["failure_threshold"], 120)  # first start compiles with Maven
        self.assertNotIn("disabled", p)
        env = dict(e.split("=", 1) for e in p["environment"])
        self.assertIn("http://127.0.0.1:8765", env["CELFAKER_FRAME_ANCESTORS"])
        names = {s["name"]: s["url"] for s in json.loads(env["CELFAKER_SERVICES"])}
        self.assertEqual(names["api"], "http://localhost:8081")
        self.assertEqual(names["core"], "http://localhost:8080/core")  # war behind JBoss
        self.assertNotIn("celfaker", names)
        # the dashboard row links to the studio, not to the probe endpoint
        import devctl.pc as pc
        pc.available = lambda: False
        self.assertEqual(ops.status(cfg, with_health=False)["services"]["celfaker"]["url"], "http://localhost:8110/")
        # built-ins are derived: saving the config never writes them
        self.config.save(cfg)
        self.assertNotIn("celfaker", json.loads(self.config.CONFIG_FILE.read_text())["services"])
        # autostart off => disabled until started by hand; enabled=false => no service at all
        cfg["celfaker"]["autostart"] = False
        self.config.save(cfg)
        cfg2 = self.config.load()
        self.assertTrue(compose.build_project(cfg2, self.config.state())["processes"]["celfaker"]["disabled"])
        cfg2["celfaker"]["enabled"] = False
        self.config.save(cfg2)
        self.assertNotIn("celfaker", self.config.load()["services"])

    def test_user_defined_celfaker_service_wins(self):
        cfg = self.config.load()
        cfg["services"]["celfaker"] = {"kind": "command", "cmd": "echo mine", "cwd": "."}
        self.config.save(cfg)
        self.assertEqual(self.config.load()["services"]["celfaker"]["cmd"], "echo mine")

    def test_java_home_falls_back_to_homebrew_keg(self):
        from devctl import javahome
        keg = self.tmp / "opt" / "openjdk@25" / "libexec" / "openjdk.jdk" / "Contents" / "Home"
        keg.mkdir(parents=True)
        real = javahome.os.path.isdir
        javahome.os.path.isdir = lambda p: str(p).startswith("/opt/homebrew/opt/openjdk@25") or real(p)
        try:
            self.assertEqual(javahome.resolve("auto:25"), "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home")
        finally:
            javahome.os.path.isdir = real

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
