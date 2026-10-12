"""API Lab end to end: Java-source discovery of a Spring fixture -> thread suggestions -> AI-style decisions ->
fake data honouring constraints -> auto workflow -> run against a strict stub (threads must work) -> k6 export."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIX = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(FIX))


class LabTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        os.environ["LOCALDEV_HOME"] = str(self.tmp / "home")
        for m in [m for m in sys.modules if m.startswith("devctl")]:
            del sys.modules[m]
        from devctl import config
        import shop_stub
        self.config = config
        self.stub = shop_stub.serve(0)
        threading.Thread(target=self.stub.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.stub.server_address[1]}"
        cfg = config.load()
        cfg.update(workspace=str(self.tmp), builds_dir=str(self.tmp / "b"), apilab_dir=str(self.tmp / "lab"), loadtest_dir=str(self.tmp / "lt"))
        config.save(cfg)
        from devctl.lab import flow, service, store
        self.lab, self.flow, self.store = service, flow, store

    def tearDown(self):
        self.stub.shutdown()

    def cfg(self):
        return self.config.load()

    def _project(self):
        self.lab.create(self.cfg(), "shop", str(FIX / "shop"), base_url=self.base)
        r = self.lab.run_discovery(self.cfg(), "shop", "source")
        self.assertEqual(r["source"], "source")
        self.assertEqual(r["apis"], 7)
        return r

    def test_full_flow(self):
        self._project()
        cfg = self.cfg()
        d = self.store.pdir(cfg, "shop")
        for sub in ("apis", "rules", "data", "workflows", "runs"):
            self.assertTrue((d / sub).is_dir())
        self.assertTrue((d / "apis" / "orderPlace.json").exists())
        top = [l for l in self.lab.links_view(cfg, "shop") if l["confidence"] >= 0.9]
        self.assertEqual({(l["from"]["api"], l["to"]["api"], l["to"]["field"]) for l in top},
                         {("customerCreate", "customerGet", "path.id"), ("customerCreate", "orderPlace", "body.customerId"),
                          ("orderPlace", "orderGet", "path.orderId"), ("orderPlace", "orderPay", "path.orderId"),
                          ("orderPlace", "orderCancel", "path.orderId")})
        # the AI decides: accept the right ones, reject a wrong one, add one by hand
        self.lab.link_status(cfg, "shop", [l["id"] for l in top], "accepted")
        wrong = next(l for l in self.lab.links_view(cfg, "shop") if l["from"]["api"] == "customerGet" and l["to"]["field"] == "body.email")
        self.lab.link_status(cfg, "shop", [wrong["id"]], "rejected")
        with self.assertRaises(ValueError):
            self.lab.link_set(cfg, "shop", "orderPlace", "nope", "orderPay", "path.orderId")
        # fake-data rules + constraints
        with self.assertRaises(ValueError):
            self.lab.set_rules(cfg, "shop", "customerCreate", {"body.nope": {"kind": "email"}})
        with self.assertRaises(ValueError):
            self.lab.set_rules(cfg, "shop", "customerCreate", {"body.email": {"kind": "telepathy"}})
        with self.assertRaises(ValueError):  # enum from the code is authoritative
            self.lab.set_rules(cfg, "shop", "customerCreate", {"body.tier": {"enum": ["BRONZE", "GOLD"]}})
        self.lab.set_rules(cfg, "shop", "customerCreate", {"body.tier": {"enum": ["GOLD", "PLATINUM"]}})
        prev = self.lab.set_rules(cfg, "shop", "orderPay", {"body.currency": {"enum": ["EUR", "USD"]}, "body.amount": {"kind": "money", "min": 5, "max": 50}})
        self.assertIn(prev["preview"]["body"]["currency"], ("EUR", "USD"))
        rows = self.lab.generate(cfg, "shop", "customerCreate", 25)["rows"]
        for r in rows:
            b = r["body"]
            self.assertRegex(b["email"], r"^[^@\s]+@[^@\s]+\.\w+$")
            self.assertRegex(b["countryCode"], r"^[A-Z]{2}$")
            self.assertIn(b["tier"], ("GOLD", "PLATINUM"))
            self.assertLessEqual(len(b["firstName"]), 40)
        orders = self.lab.generate(cfg, "shop", "orderPlace", 5)["rows"]
        self.assertEqual(orders[0]["body"]["customerId"], {"$link": "customerCreate.id"})
        self.assertTrue(all(1 <= l["quantity"] <= 10 for o in orders for l in o["body"]["lines"]))
        self.assertTrue((d / "data" / "orderPlace.json").exists())
        # workflow from accepted threads
        wf = self.flow.auto(cfg, "shop", "checkout")
        order = [s["api"] for s in wf["steps"]]
        self.assertLess(order.index("customerCreate"), order.index("orderPlace"))
        self.assertLess(order.index("orderPlace"), order.index("orderPay"))
        self.assertEqual(order[-1], "orderCancel")
        place = next(s for s in wf["steps"] if s["api"] == "orderPlace")
        cust = next(s for s in wf["steps"] if s["api"] == "customerCreate")
        self.assertEqual(place["bind"], {"body.customerId": "{{" + cust["id"] + ".response.id}}"})
        # run against the strict stub: every thread must carry a real value
        run = self.flow.run(self.cfg(), "shop", "checkout", iterations=3)
        self.assertEqual(run["failed"], 0, json.dumps(run["iterations"][0], default=str)[:2000])
        self.assertEqual(run["ok"], 3 * len(wf["steps"]))
        step = next(s for s in run["iterations"][0] if s["api"] == "orderPlace")
        self.assertRegex(step["bound"]["body.customerId"]["value"], r"^[0-9a-f-]{36}$")
        pool = self.store.pool(self.cfg(), "shop")
        self.assertEqual(len(pool["customerCreate.id"]), 3)
        # captured real ids replace link markers in newly generated data
        again = self.lab.generate(self.cfg(), "shop", "orderPlace", 3)["rows"]
        self.assertIn(again[0]["body"]["customerId"], pool["customerCreate.id"])
        self.assertEqual(len(self.store.runs(self.cfg(), "shop")), 1)
        # k6 export
        ex = self.flow.export_k6(self.cfg(), "shop", "checkout", profile="smoke", vus=1, duration="5s", rows=10)
        js = Path(ex["script"]).read_text()
        self.assertIn("SharedArray", js)
        self.assertEqual(len(json.loads(Path(ex["data"]).read_text())), 10)
        self.assertEqual(self.cfg()["loadtests"][ex["loadtest"]]["apilab"], {"project": "shop", "workflow": "checkout"})
        if shutil.which("node"):
            m = Path(self.tmp / "check.mjs")
            m.write_text(js)
            r = subprocess.run(["node", "--check", str(m)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
        self.k6_script = ex["script"]

    def test_threads_matter_and_validation(self):
        self._project()
        cfg = self.cfg()
        with self.assertRaises(ValueError):
            self.lab.workflow_save(cfg, "shop", {"id": "bad", "steps": [{"id": "s1", "api": "orderPlace", "bind": {"body.customerId": "{{s2.response.id}}"}},
                                                                         {"id": "s2", "api": "customerCreate"}]})
        with self.assertRaises(ValueError):
            self.lab.workflow_save(cfg, "shop", {"id": "bad2", "steps": [{"api": "orderPlace", "bind": {"body.nope": "x"}}]})
        self.lab.workflow_save(cfg, "shop", {"id": "unthreaded", "steps": [{"id": "s1", "api": "customerCreate"}, {"id": "s2", "api": "orderPlace"}]})
        from devctl import tools
        text, err = tools.call("apilab_workflow_run", {"project": "shop", "workflow": "unthreaded"})
        self.assertFalse(err)
        res = json.loads(text)
        self.assertEqual(res["failed"], 1)
        self.assertEqual(res["failures"][0]["status"], 422)  # unknown customer: the missing thread is visible
        text, err = tools.call("apilab_link_set", {"project": "shop", "from_api": "customerCreate", "from_field": "id",
                                                    "to_api": "orderPlace", "to_field": "body.customerId", "reason": "orders belong to customers"})
        self.assertFalse(err, text)
        self.assertEqual(json.loads(text)["status"], "accepted")
        self.lab.workflow_save(cfg, "shop", {"id": "threaded", "steps": [{"id": "s1", "api": "customerCreate"},
                                                                          {"id": "s2", "api": "orderPlace", "bind": {"body.customerId": "{{s1.response.id}}"}},
                                                                          {"id": "s3", "api": "orderGet", "bind": {"path.orderId": "{{s2.response.id}}"}}]})
        res = json.loads(tools.call("apilab_workflow_run", {"project": "shop", "workflow": "threaded", "iterations": 2})[0])
        self.assertEqual(res["failed"], 0, res)

    def test_openapi_discovery_and_routes(self):
        from devctl.lab import discover
        doc = {"paths": {"/pets": {"post": {"operationId": "addPet", "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewPet"}}}},
                                            "responses": {"201": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Pet"}}}}}}},
                         "/pets/{petId}": {"get": {"operationId": "getPet", "parameters": [{"name": "petId", "in": "path", "required": True, "schema": {"type": "integer"}}],
                                                   "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Pet"}}}}}}}},
               "components": {"schemas": {"NewPet": {"type": "object", "required": ["name"], "properties": {"name": {"type": "string", "maxLength": 20},
                                                                                                          "tags": {"type": "array", "items": {"type": "string"}}}},
                                          "Pet": {"allOf": [{"$ref": "#/components/schemas/NewPet"}, {"type": "object", "properties": {"id": {"type": "integer"}}}]}}}}
        apis = {a["id"]: a for a in discover.from_openapi(doc)}
        self.assertEqual({f["path"] for f in apis["addPet"]["request"]}, {"body.name", "body.tags"})
        self.assertIn("id", {f["path"] for f in apis["addPet"]["response"]})
        from devctl.lab import links
        self.assertTrue(any(l["from"]["api"] == "addPet" and l["to"]["field"] == "path.petId" for l in links.suggest(list(apis.values()))))
        # dashboard routes
        self._project()
        from devctl import server
        from http.server import ThreadingHTTPServer
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(0))
        port = srv.server_address[1]
        srv.RequestHandlerClass = server.make_handler(port)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            self.assertIn(b"API Lab", urllib.request.urlopen(f"http://127.0.0.1:{port}/lab").read())
            self.assertIn(b"renderThreads", urllib.request.urlopen(f"http://127.0.0.1:{port}/static/lab.js").read())
            g = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/api/lab/shop/graph"))
            self.assertEqual(len(g["apis"]), 7)
            req = urllib.request.Request(f"http://127.0.0.1:{port}/api/lab/shop/workflows/auto", data=b"{}", method="POST",
                                         headers={"X-Devctl": "1", "Content-Type": "application/json"})
            with self.assertRaises(urllib.error.HTTPError) as e:  # nothing accepted yet
                urllib.request.urlopen(req)
            self.assertEqual(e.exception.code, 400)
        finally:
            srv.shutdown()
        from devctl import mcp
        names = {t["name"] for t in mcp.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})["result"]["tools"]}
        self.assertTrue({"apilab_discover", "apilab_link_set", "apilab_set_rules", "apilab_workflow_run", "apilab_export_k6"} <= names)
        p = mcp.handle({"jsonrpc": "2.0", "id": 2, "method": "prompts/get", "params": {"name": "design-test-data", "arguments": {"project": "shop"}}})
        self.assertIn("apilab_discover", p["result"]["messages"][0]["content"]["text"])


if __name__ == "__main__":
    unittest.main()
