import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


def sh(*a, cwd=None):
    subprocess.run(a, cwd=cwd, check=True, capture_output=True)


STUB = """#!/usr/bin/env bash
# stand-in for scripts/db/liquibase.sh: records calls, fails migrate when $STUB_FAIL_MIGRATE is set
echo "$1 $2 mig=$MIGRATIONS_DIR env=$APP_ENV db=$DB_HOST/$DB_NAME user=$DB_USERNAME id=$DEPLOY_ID" >> "$STUB_LOG"
[ "$1" = migrate ] && [ -n "$STUB_FAIL_MIGRATE" ] && { echo "boom"; exit 1; }
exit 0
"""


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        os.environ["LOCALDEV_HOME"] = str(self.tmp / "home")
        os.environ["STUB_LOG"] = str(self.tmp / "stub.log")
        os.environ.pop("STUB_FAIL_MIGRATE", None)
        os.environ["QA_PW"] = "s3cret"
        for m in [m for m in sys.modules if m.startswith("devctl")]:
            del sys.modules[m]
        from devctl import config
        self.config = config
        ws, self.builds_dir = self.tmp / "ws", self.tmp / "b"
        repo = ws / "orders"
        (repo / "db-migrations" / "changelog").mkdir(parents=True)
        (repo / "db-migrations" / "changelog" / "x.yaml").write_text("databaseChangeLog: []")
        (repo / "pom.xml").write_text("<project/>")
        (repo / "marker.txt").write_text("main\n")
        sh("git", "init", "-q", "-b", "main", cwd=repo)
        g = ["git", "-c", "user.email=a@b", "-c", "user.name=t"]
        sh(*g, "add", ".", cwd=repo)
        sh(*g, "commit", "-qm", "init", cwd=repo)
        sh("git", "checkout", "-q", "-b", "bad", cwd=repo)
        (repo / "marker.txt").write_text("bad\n")
        sh(*g, "commit", "-qam", "bad", cwd=repo)
        sh("git", "checkout", "-q", "main", cwd=repo)
        sh("git", "branch", "release/1", cwd=repo)
        stub = self.tmp / "stub.sh"
        stub.write_text(STUB)
        stub.chmod(stub.stat().st_mode | stat.S_IXUSR)
        deployed = self.builds_dir / "deployed" / "orders-qa" / "app.jar"

        class H(BaseHTTPRequestHandler):
            def do_GET(h):
                bad = deployed.exists() and "bad" in deployed.read_text()
                h.send_response(500 if bad else 200)
                h.end_headers()

            def log_message(h, *a):
                pass

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        port = self.srv.server_address[1]
        cfg = config.load()
        cfg.update({
            "workspace": str(ws), "builds_dir": str(self.builds_dir), "java_home": "/jdk",
            "liquibase": {"script": str(stub), "migrations_dir": ""},
            "repos": {"orders": {"build_cmd": "mkdir -p target && cp marker.txt target/orders-1.jar",
                                 "artifact_globs": ["**/target/*.jar"]}},
            "services": {"orders-qa": {"kind": "jar", "repo": "orders", "artifact": "orders-*.jar", "port": 1,
                                       "health": {"url": f"http://127.0.0.1:{port}/"}}},
            "environments": {"qa": {"services": ["orders-qa"], "health_timeout": 1, "auto_rollback": True,
                                    "allowed_branches": ["main", "bad", "release/*"],
                                    "db": {"type": "postgresql", "host": "dbhost", "name": "orders_qa",
                                           "username": "app", "password_env": "QA_PW"}}}})
        config.save(cfg)
        self.cfg = config.load()

    def tearDown(self):
        self.srv.shutdown()

    def run_dep(self, branch="main", **kw):
        from devctl import deployments
        m = deployments.create(self.cfg, "qa", "orders", branch, **kw)
        rc = deployments.run(self.cfg, m["id"])
        return rc, deployments.read(self.cfg, m["id"])

    def stub_calls(self):
        p = Path(os.environ["STUB_LOG"])
        return p.read_text().splitlines() if p.exists() else []

    def test_success_builds_migrates_deploys_verifies(self):
        rc, m = self.run_dep()
        self.assertEqual((rc, m["status"]), (0, "success"), m)
        self.assertEqual([s["name"] for s in m["steps"]], ["build", "database migrate", "deploy orders-qa", "verify health"])
        calls = self.stub_calls()
        self.assertEqual(len(calls), 1)
        self.assertIn("migrate", calls[0])
        self.assertIn(f"mig={self.builds_dir}/runs/{m['build_id']}/migrations", calls[0])  # snapshot of THIS build
        self.assertIn("db=dbhost/orders_qa user=app", calls[0])
        self.assertEqual(m["db"]["tag"], f"pre-deploy-{m['id']}")
        self.assertEqual((self.builds_dir / "deployed/orders-qa/app.jar").read_text().strip(), "main")

    def test_unhealthy_deploy_rolls_back_services_and_database(self):
        rc, ok = self.run_dep("main")
        self.assertEqual(ok["status"], "success")
        os.unlink(os.environ["STUB_LOG"])
        rc, m = self.run_dep("bad")
        self.assertEqual((rc, m["status"]), (1, "rolled_back"), m)
        self.assertEqual((self.builds_dir / "deployed/orders-qa/app.jar").read_text().strip(), "main")  # previous build back
        calls = self.stub_calls()
        self.assertTrue(calls[0].startswith("migrate"), calls)
        self.assertTrue(calls[1].startswith("rollback " + m["db"]["tag"]), calls)
        self.assertTrue(m["db"]["rolled_back"])
        self.assertEqual(self.config.state()["deployed"]["orders-qa"]["branch"], "main")
        self.assertIn("rollback database", [s["name"] for s in m["steps"]])

    def test_first_ever_deploy_failure_undeploys(self):
        rc, m = self.run_dep("bad")
        self.assertEqual(m["status"], "rolled_back", m)
        self.assertNotIn("orders-qa", self.config.state().get("deployed", {}))
        self.assertFalse((self.builds_dir / "deployed/orders-qa").exists())

    def test_migration_failure_leaves_services_alone(self):
        os.environ["STUB_FAIL_MIGRATE"] = "1"
        rc, m = self.run_dep()
        self.assertEqual((rc, m["status"]), (1, "failed"))
        self.assertTrue(m["db"]["rolled_back"])
        self.assertNotIn("orders-qa", self.config.state().get("deployed", {}))
        self.assertEqual(len(self.stub_calls()), 1)  # liquibase.sh does its own rollback; no second call

    def test_auto_rollback_off_keeps_failed_state(self):
        self.cfg["environments"]["qa"]["auto_rollback"] = False
        self.config.save(self.cfg)
        rc, m = self.run_dep("bad")
        self.assertEqual(m["status"], "failed")
        self.assertIn("orders-qa", self.config.state()["deployed"])

    def test_build_failure(self):
        self.cfg["repos"]["orders"]["build_cmd"] = "exit 3"
        self.config.save(self.cfg)
        rc, m = self.run_dep()
        self.assertEqual(m["status"], "failed")
        self.assertIn("build", m["error"])

    def test_validation_and_cancel_and_busy(self):
        from devctl import deployments
        with self.assertRaisesRegex(ValueError, "not allowed"):
            deployments.create(self.cfg, "qa", "orders", "feature/x")
        with self.assertRaisesRegex(ValueError, "unknown environment"):
            deployments.create(self.cfg, "nope", "orders", "main")
        future = time.time() + 3600
        m = deployments.create(self.cfg, "qa", "orders", "main", run_at=future)
        self.assertEqual(deployments.tick(self.cfg), [])  # not due
        self.assertEqual(deployments.cancel(self.cfg, m["id"])["status"], "cancelled")
        self.assertEqual(deployments.tick(self.cfg, future + 1), [])  # cancelled stays cancelled
        a = deployments.create(self.cfg, "qa", "orders", "main", run_at=1)
        b = deployments.create(self.cfg, "qa", "orders", "release/1", run_at=2)
        started = []
        orig = deployments.start
        deployments.start = lambda cfg, i: started.append(i) or (_ for _ in ()).throw(ValueError("busy")) if len(started) else started.append(i) or {}
        try:
            deployments.tick(self.cfg)
        finally:
            deployments.start = orig
        self.assertEqual(started, [a["id"], b["id"]])  # oldest due first; a busy env just retries next tick

    def test_busy_environment_blocks_start(self):
        from devctl import deployments
        a = deployments.create(self.cfg, "qa", "orders", "main")
        meta = deployments.read(self.cfg, a["id"])
        meta.update(status="running", pid=os.getpid())
        deployments._write(self.cfg, meta)
        b = deployments.create(self.cfg, "qa", "orders", "main")
        with self.assertRaisesRegex(ValueError, "busy"):
            deployments.start(self.cfg, b["id"])

    def test_dead_runner_is_reaped(self):
        from devctl import deployments
        a = deployments.create(self.cfg, "qa", "orders", "main")
        meta = deployments.read(self.cfg, a["id"])
        meta.update(status="running", pid=2 ** 22 + 12345)
        deployments._write(self.cfg, meta)
        self.assertEqual(deployments.list_all(self.cfg)[0]["status"], "failed")

    def test_start_spawns_detached_runner_end_to_end(self):
        from devctl import deployments
        m = deployments.create(self.cfg, "qa", "orders", "main")
        deployments.start(self.cfg, m["id"])
        for _ in range(120):
            cur = deployments.read(self.cfg, m["id"])
            if cur["status"] != "running":
                break
            time.sleep(0.5)
        self.assertEqual(cur["status"], "success", deployments.read_log(self.cfg, m["id"])["text"])

    def test_environment_validation_and_secrets(self):
        from devctl import dbrun, envs
        with self.assertRaises(ValueError):
            envs.normalize(self.cfg, "Bad Name", {})
        with self.assertRaises(ValueError):
            envs.normalize(self.cfg, "x", {"db": {"type": "db2"}})
        with self.assertRaises(ValueError):
            envs.normalize(self.cfg, "x", {"db": {"type": "mysql", "host": "h; rm -rf /"}})
        with self.assertRaises(ValueError):
            envs.normalize(self.cfg, "x", {"services": ["ghost"]})
        e = envs.save(self.cfg, "stage", {"services": ["orders-qa"], "db": {"type": "mysql", "host": "h", "password_env": "STAGE_PW"}})
        cfg = self.config.load()
        self.assertNotIn("s3cret", json.dumps(cfg))  # secrets never land in config.json
        with self.assertRaisesRegex(ValueError, "STAGE_PW"):
            dbrun.db_env(cfg, "stage")
        self.assertEqual(dbrun.db_env(self.cfg, "qa")["DB_PASSWORD"], "s3cret")
        envs.delete(cfg, "stage")
        self.assertNotIn("stage", self.config.load()["environments"])

    def test_liquibase_request_runs_and_is_logged(self):
        from devctl import dbrun
        with self.assertRaises(ValueError):
            dbrun._validate("rollback", "x; rm")
        with self.assertRaises(ValueError):
            dbrun._validate("drop-all", "")
        dbrun.request_file("qa").parent.mkdir(parents=True, exist_ok=True)
        dbrun.request_file("qa").write_text(json.dumps({"action": "status", "arg": ""}))
        self.assertEqual(dbrun.process_request(self.cfg, "qa"), 0)
        self.assertFalse(dbrun.request_file("qa").exists())  # consumed: a restart is a no-op, not a repeat migrate
        self.assertEqual(dbrun.process_request(self.cfg, "qa"), 0)
        last = dbrun.last(self.cfg, "qa")
        self.assertEqual(last["status"], "success")
        self.assertIn("status", self.stub_calls()[0])

    def test_lock_is_exclusive_and_stale_reclaimed(self):
        from devctl import dbrun
        with dbrun.locked(self.cfg, "qa"):
            with self.assertRaisesRegex(ValueError, "already running"):
                with dbrun.locked(self.cfg, "qa"):
                    pass
        lk = dbrun.jobs_dir(self.cfg, "qa") / "lock"
        lk.write_text("4194999")  # dead pid
        with dbrun.locked(self.cfg, "qa"):
            pass

    def test_process_compose_hosts_liquibase_and_scheduler(self):
        from devctl import compose
        proj = compose.build_project(self.cfg, {"deployed": {}})["processes"]
        self.assertIn("liquibase-qa", proj)
        self.assertEqual(proj["liquibase-qa"]["availability"]["restart"], "no")
        self.assertIn("_db qa", proj["liquibase-qa"]["command"])
        self.assertEqual(proj["deploy-scheduler"]["availability"]["restart"], "always")
        self.assertNotIn("s3cret", json.dumps(proj))

    def test_dashboard_api(self):
        from devctl import server
        r = server.route("GET", "/api/environments", {}, {})
        self.assertTrue(r["qa"]["db_configured"])
        m = server.route("POST", "/api/deployments", {}, {"environment": "qa", "repo": "orders", "branch": "main",
                                                           "run_at": time.time() + 3600})
        self.assertEqual(m["status"], "scheduled")
        lst = server.route("GET", "/api/deployments", {}, {})
        self.assertEqual(lst["items"][0]["id"], m["id"])
        server.route("PUT", "/api/environments/dev", {}, {"services": ["orders-qa"], "description": "d"})
        server.route("DELETE", "/api/environments/dev", {}, {})
        self.assertEqual(server.route("POST", f"/api/deployments/{m['id']}/cancel", {}, {})["status"], "cancelled")


if __name__ == "__main__":
    unittest.main()
