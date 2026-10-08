"""Performance, load-test and JFR features. Unit tests always run; the end-to-end test needs a JDK (java/jps/jcmd)
and runs the repository's real JFR analyzer when a JDK 25 is available (DEVCTL_TEST_JDK25 or /usr/lib/jvm/java-25*)."""
import glob
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIX = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(ROOT))


def fresh(home: Path):
    os.environ["LOCALDEV_HOME"] = str(home)
    for m in [m for m in sys.modules if m.startswith("devctl")]:
        del sys.modules[m]
    import devctl.config
    return devctl.config


PROM = """# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_count{method="GET",status="200",uri="/api/a"} %(a)s
http_server_requests_seconds_count{method="GET",status="500",uri="/api/a"} %(e)s
http_server_requests_seconds_sum{method="GET",status="200",uri="/api/a"} %(s)s
http_server_requests_seconds_max{method="GET",status="200",uri="/api/a"} 0.2
http_server_requests_seconds_count{method="GET",status="200",uri="/actuator/prometheus"} 500
http_server_requests_seconds_bucket{method="GET",status="200",uri="/api/a",le="0.1"} %(b1)s
http_server_requests_seconds_bucket{method="GET",status="200",uri="/api/a",le="0.2"} %(b2)s
http_server_requests_seconds_bucket{method="GET",status="200",uri="/api/a",le="+Inf"} %(t)s
jvm_memory_used_bytes{area="heap",id="eden"} 104857600
jvm_memory_used_bytes{area="nonheap",id="meta"} 1
jvm_memory_max_bytes{area="heap",id="eden"} 209715200
process_cpu_usage 0.25
jvm_threads_live_threads 42
hikaricp_connections_active{pool="p"} 3
"""


class UnitTest(unittest.TestCase):
    def setUp(self):
        self.config = fresh(Path(tempfile.mkdtemp()))

    def test_prometheus_rates_and_quantiles(self):
        from devctl import metrics
        s0 = metrics.extract_prom(metrics.parse_prom(PROM % dict(a=90, e=10, s=5.0, b1=50, b2=90, t=100)))
        self.assertEqual(s0["req_count"], 100)  # actuator traffic excluded
        s1 = metrics.extract_prom(metrics.parse_prom(PROM % dict(a=180, e=20, s=10.0, b1=100, b2=180, t=200)))
        sm = metrics.Sampler("x", None, None)
        sm.prev, sm.prev_t = s0, 100.0
        p = sm._derive(s1, 110.0)
        self.assertEqual(p["rps"], 10.0)
        self.assertEqual(p["avg_ms"], 50.0)
        self.assertEqual(p["err_pct"], 10.0)
        self.assertEqual(p["heap_mb"], 100.0)
        self.assertEqual(p["heap_max_mb"], 200.0)
        self.assertEqual(p["cpu_pct"], 25.0)
        self.assertEqual(p["threads"], 42)
        self.assertEqual(p["pool_active"], 3)
        self.assertAlmostEqual(p["p95_ms"], 200.0, delta=0.1)  # 95th of 100 falls in the (0.1, 0.2] bucket
        self.assertEqual(sm.endpoints[0]["endpoint"], "GET /api/a")
        self.assertEqual(sm.endpoints[0]["errors"], 10)
        restarted = metrics.extract_prom(metrics.parse_prom(PROM % dict(a=1, e=0, s=0.1, b1=1, b2=1, t=1)))
        sm.prev = s1
        self.assertNotIn("rps", sm._derive(restarted, 120.0))  # counter reset after restart is not a negative rate

    def test_discovery_and_k6_generation(self):
        from devctl import loadgen
        eps = loadgen.from_openapi({"paths": {"/api/o/{id}": {"get": {"parameters": [{"name": "id", "in": "path"}]}},
                                              "/actuator/health": {"get": {}}}})
        self.assertEqual(eps, [{"method": "GET", "path": "/api/o/{id}", "params": ["id"], "summary": ""}])
        maps = {"contexts": {"app": {"mappings": {"dispatcherServlets": {"dispatcherServlet": [
            {"handler": "h", "details": {"requestMappingConditions": {"methods": ["POST"], "patterns": ["/api/o"]}}},
            {"handler": "e", "details": {"requestMappingConditions": {"methods": [], "patterns": ["/error"]}}}]}}}}}
        self.assertEqual([(e["method"], e["path"]) for e in loadgen.from_mappings(maps)], [("POST", "/api/o")])
        js = loadgen.generate("http://localhost:1/", eps, "stress", 5, "30s", {"id": ["1", "2"]}, p95_ms=300)
        self.assertIn('"target": 15', js)
        self.assertIn("p(95)<300", js)
        self.assertIn('"http://localhost:1"', js)
        if shutil.which("node"):  # syntax check (k6 imports are not resolved by --check)
            f = Path(tempfile.mkdtemp()) / "t.mjs"
            f.write_text(js)
            r = subprocess.run(["node", "--check", str(f)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
        with self.assertRaises(ValueError):
            loadgen.generate("http://x", eps, "load", 1, "1 minute")

    def test_k6_summary_parsing(self):
        from devctl import loadrun
        r = loadrun.k6_results({"metrics": {"http_reqs": {"count": 10, "rate": 2.5},
                                            "http_req_duration": {"avg": 5, "p(95)": 9, "thresholds": {"p(95)<500": False}},
                                            "http_req_failed": {"passes": 1, "fails": 9}}})
        self.assertEqual(r["rps"], 2.5)
        self.assertEqual(r["latency_ms"]["p(95)"], 9)
        self.assertAlmostEqual(r["error_rate"], 0.1)
        self.assertEqual(r["thresholds"], ["http_req_duration: p(95)<500"])

    def test_jfr_flags_in_project(self):
        from devctl import compose, jfr
        cfg = self.config.load()
        cfg["services"] = {"api": {"kind": "jar", "port": 8081}, "quiet": {"kind": "jar", "port": 8082, "jfr": {"auto": False}}}
        cfg["jboss"]["home"], cfg["jboss"]["always_on"] = "/opt/eap", True
        proj = compose.build_project(cfg, {"deployed": {"api": {}, "quiet": {}}})
        cmd = proj["processes"]["api"]["command"]
        self.assertIn("-Ddevctl.service=api", cmd)
        self.assertIn("-XX:StartFlightRecording:name=devctl,settings=profile,maxage=30m", cmd)
        self.assertIn("dumponexit=true", cmd)
        self.assertNotIn("StartFlightRecording", proj["processes"]["quiet"]["command"])
        self.assertIn("-Ddevctl.service=quiet", proj["processes"]["quiet"]["command"])
        self.assertIn("-Ddevctl.service=jboss-eap", " ".join(proj["processes"]["jboss-eap"]["environment"]))
        with self.assertRaises(ValueError):
            jfr.resolve(cfg, "../../etc/passwd.jfr")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def jdk25():
    c = os.environ.get("DEVCTL_TEST_JDK25") or next(iter(sorted(glob.glob("/usr/lib/jvm/java-25*"))), "")
    return c if c and os.path.exists(os.path.join(c, "bin", "java")) else ""


@unittest.skipUnless(shutil.which("java") and shutil.which("jps") and shutil.which("jcmd"), "needs a JDK")
class EndToEndTest(unittest.TestCase):
    """Real JVM with devctl's JFR flags -> live metrics -> discovery -> load run (fake k6) -> JFR -> real analysis."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.config = fresh(self.tmp / "home")
        self.port = free_port()
        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "k6").symlink_to(FIX / "fake_k6.py")
        self.old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{bin_dir}:{self.old_path}"
        os.environ["DEMO_URL"] = f"http://127.0.0.1:{self.port}"
        cfg = self.config.load()
        jh = str(Path(shutil.which("java")).resolve().parent.parent)
        cfg.update({"workspace": str(self.tmp), "builds_dir": str(self.tmp / "b"), "loadtest_dir": str(self.tmp / "lt"), "java_home": jh,
                    "services": {"demo": {"kind": "jar", "port": self.port, "jfr": {"packages": ["Demo"]}}}})
        cfg["infra"]["enabled"] = []
        self.analyzer = jdk25() and (ROOT.parent / "scripts" / "jfr-analyze.sh").exists()
        cfg["jfr"].update(analyzer_java_home=jdk25(), auto_analyze=bool(self.analyzer))
        if not self.analyzer:
            cfg["jfr"]["analyzer_cmd"] = "false"
        self.config.save(cfg)
        from devctl import jfr
        flags = jfr.jvm_flags(cfg, "demo", cfg["services"]["demo"])
        self.proc = subprocess.Popen(f"exec java {flags} {FIX / 'Demo.java'} {self.port}", shell=True,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(120):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}/actuator/health", timeout=1)
                break
            except Exception:
                time.sleep(0.5)
        self.cfg = self.config.load()

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        self.proc.terminate()
        self.proc.wait(timeout=30)

    def _wait(self, fn, secs=240):
        end = time.time() + secs
        while time.time() < end:
            v = fn()
            if v:
                return v
            time.sleep(1)
        self.fail("timed out")

    def test_pipeline(self):
        from devctl import jfr, jvm, loadgen, loadrun, metrics
        # JVM discovery by marker
        self.assertTrue(any(j["service"] == "demo" for j in jvm.list_jvms(self.cfg)))
        # readiness: health + prometheus + api discovery
        ready = {c["check"]: c["ok"] for c in metrics.readiness(self.cfg, "demo")}
        self.assertTrue(ready["Prometheus metrics"])
        self.assertTrue(ready["API discovery for load tests"])
        # live sampling under traffic
        s = metrics.sampler_for(self.cfg, "demo")
        s.sample()
        for i in range(30):
            urllib.request.urlopen(f"http://127.0.0.1:{self.port}/api/orders/{i + 1}").read()
        pt = s.sample()
        self.assertEqual(s.mode, "prometheus")
        self.assertGreater(pt["rps"], 0)
        self.assertIsNotNone(pt["p95_ms"])
        self.assertTrue(pt["up"])
        self.assertEqual(s.endpoints[0]["endpoint"], "GET /api/orders/{id}")
        # setup: discover + create
        d = loadgen.discover(self.cfg, "demo")
        self.assertEqual(d["source"], "openapi")
        get = [e for e in d["endpoints"] if e["method"] == "GET"]
        made = loadgen.create(self.cfg, "demo-load", "demo", get, "smoke", 1, "5s", path_values={"id": ["1", "2"]})
        self.assertTrue(Path(made["script"]).exists())
        self.cfg = self.config.load()
        # full load run: JFR start -> (fake) k6 -> timeline -> JFR stop -> analysis
        run = loadrun.start(self.cfg, "demo-load")
        m = self._wait(lambda: (lambda r: r if r["status"] != "running" else None)(loadrun.read(self.cfg, run["id"])))
        log = loadrun.log(self.cfg, run["id"])["text"]
        self.assertIn(m["status"], ("passed", "thresholds_failed"), log)
        self.assertGreater(m["k6"]["requests"], 0)
        self.assertGreater(len(loadrun.timeline(self.cfg, run["id"])), 0)
        self.assertIn("rps", m["service_summary"])
        self.assertEqual(m["jfr"]["status"], "recorded", m["jfr"])
        rec_id = m["jfr"]["recording"]
        self.assertGreater(jfr.resolve(self.cfg, rec_id).stat().st_size, 0)
        if self.analyzer:
            self.assertEqual(m["jfr"]["analysis"]["status"], "success", log)
            summ = jfr.summary(self.cfg, rec_id)
            self.assertEqual(summ["schemaVersion"], "jfr-analyzer/summary/1")
            self.assertIn(summ["executiveSummary"]["status"], ("GREEN", "AMBER", "RED", "UNKNOWN"))
            locs = json.dumps(summ["topHotspots"]["cpu"])
            self.assertIn("Demo", locs)  # attributed to the -p package
        # snapshot of the continuous recording
        snap = jfr.snapshot(self.cfg, "demo")
        self.assertTrue(jfr.resolve(self.cfg, snap["recording"]).exists())
        if self.analyzer:
            self._wait(lambda: jfr.analysis(jfr.resolve(self.cfg, snap["recording"]))["status"] == "success")
            data, ctype = jfr.report_file(self.cfg, snap["recording"], "report.html")
            self.assertTrue(ctype.startswith("text/html") and len(data) > 1000)
        kinds = {r["kind"] for r in jfr.list_recordings(self.cfg, "demo")}
        self.assertTrue({"loadtest", "snapshot"} <= kinds)


if __name__ == "__main__":
    unittest.main()
