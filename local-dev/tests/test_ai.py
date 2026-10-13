"""MCP protocol (stdio + HTTP), agent configuration, goal-level agent tools and the dashboard assistant loop.
The assistant test drives the real anthropic SDK against a local fake Messages API when the SDK is installed."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


def fresh(tmp: Path):
    os.environ["LOCALDEV_HOME"] = str(tmp / "home")
    for m in [m for m in sys.modules if m.startswith("devctl")]:
        del sys.modules[m]
    import devctl.config as c
    ws = tmp / "ws" / "orders"
    ws.mkdir(parents=True)
    subprocess.run(["git", "init", "-q", "-b", "main"], cwd=ws, check=True)
    (ws / "pom.xml").write_text("<project/>")
    cfg = c.load()
    cfg.update(workspace=str(tmp / "ws"), builds_dir=str(tmp / "b"),
               services={"orders-api": {"kind": "jar", "repo": "orders", "port": 1, "artifact": "*.jar"}})
    c.save(cfg)
    return c


class McpProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.config = fresh(self.tmp)
        from devctl import mcp
        self.mcp = mcp

    def rpc(self, method, params=None, id_=1):
        return self.mcp.handle({"jsonrpc": "2.0", "id": id_, "method": method, "params": params or {}})

    def test_initialize_and_catalogue(self):
        r = self.rpc("initialize", {"protocolVersion": "2025-06-18"})["result"]
        self.assertEqual(r["protocolVersion"], "2025-06-18")
        self.assertIn("ship", r["instructions"])
        self.assertEqual(self.rpc("initialize", {"protocolVersion": "1999-01-01"})["result"]["protocolVersion"], "2025-11-25")
        tools = {t["name"]: t for t in self.rpc("tools/list")["result"]["tools"]}
        self.assertGreaterEqual(len(tools), 35)
        self.assertTrue(tools["status"]["annotations"]["readOnlyHint"])
        self.assertTrue(tools["undeploy"]["annotations"]["destructiveHint"])
        self.assertFalse(tools["ship"]["annotations"]["readOnlyHint"])
        for t in tools.values():
            self.assertEqual(t["inputSchema"]["type"], "object")
            self.assertTrue(set(t["inputSchema"]["required"]) <= set(t["inputSchema"]["properties"]))

    def test_tool_calls_resources_prompts(self):
        r = self.rpc("tools/call", {"name": "list_repos", "arguments": {}})["result"]
        self.assertFalse(r["isError"])
        self.assertEqual(json.loads(r["content"][0]["text"])[0]["name"], "orders")
        r = self.rpc("tools/call", {"name": "diagnose", "arguments": {}})["result"]
        self.assertTrue(r["isError"])  # missing argument -> tool error, not a protocol error
        self.assertIn("error", self.rpc("tools/call", {"name": "nope"}))
        uris = [x["uri"] for x in self.rpc("resources/list")["result"]["resources"]]
        self.assertIn("devctl://config", uris)
        cfg = json.loads(self.rpc("resources/read", {"uri": "devctl://config"})["result"]["contents"][0]["text"])
        self.assertIn("orders-api", cfg["services"])
        self.assertIn("error", self.rpc("resources/read", {"uri": "devctl://nope"}))
        names = [p["name"] for p in self.rpc("prompts/list")["result"]["prompts"]]
        self.assertIn("ship-branch", names)
        msg = self.rpc("prompts/get", {"name": "performance-check", "arguments": {"service": "orders-api"}})["result"]
        self.assertIn("orders-api", msg["messages"][0]["content"]["text"])
        self.assertIn("10 VUs", msg["messages"][0]["content"]["text"])
        self.assertIn("error", self.rpc("prompts/get", {"name": "ship-branch", "arguments": {}}))
        self.assertIsNone(self.mcp.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        batch = self.mcp.handle([{"jsonrpc": "2.0", "id": 1, "method": "ping"}, {"jsonrpc": "2.0", "method": "x"}])
        self.assertEqual(batch, [{"jsonrpc": "2.0", "id": 1, "result": {}}])
        self.assertEqual(self.rpc("bogus")["error"]["code"], -32601)

    def test_stdio_selftest_like_an_agent(self):
        from devctl import setup
        t = setup.mcp_selftest()
        self.assertEqual(t["server"]["name"], "devctl")
        self.assertGreaterEqual(t["tools"], 35)
        self.assertEqual(t["repos_visible"], 1)

    def test_http_endpoint(self):
        from devctl import server
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(0))
        port = srv.server_address[1]
        srv.RequestHandlerClass = server.make_handler(port)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{port}/mcp"

        def post(body, headers=None):
            req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json", **(headers or {})})
            try:
                with urllib.request.urlopen(req) as r:
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                return e.code, e.read()
        try:
            st, body = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}})
            self.assertEqual(st, 200)
            self.assertEqual(json.loads(body)["result"]["serverInfo"]["name"], "devctl")
            self.assertEqual(post({"jsonrpc": "2.0", "method": "notifications/initialized"})[0], 202)
            self.assertEqual(post({"jsonrpc": "2.0", "id": 2, "method": "ping"}, {"Origin": "https://evil.example"})[0], 403)
            self.assertEqual(post({"jsonrpc": "2.0", "id": 2, "method": "ping"}, {"Origin": f"http://127.0.0.1:{port}"})[0], 200)
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(url)
            self.assertEqual(e.exception.code, 405)
            cfg = self.config.load()
            cfg["mcp"] = {"http_token": "s3cret"}
            self.config.save(cfg)
            self.assertEqual(post({"jsonrpc": "2.0", "id": 3, "method": "ping"})[0], 401)
            self.assertEqual(post({"jsonrpc": "2.0", "id": 3, "method": "ping"}, {"Authorization": "Bearer s3cret"})[0], 200)
        finally:
            srv.shutdown()


class AgentsAndOpsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.config = fresh(self.tmp)
        self.old_home = os.environ.get("HOME")
        os.environ["HOME"] = str(self.tmp / "userhome")

    def tearDown(self):
        os.environ["HOME"] = self.old_home

    def test_agent_configs_install_and_merge(self):
        from devctl import agents
        cfg = self.config.load()
        sn = agents.snippets(cfg)
        for a in ("claude-code", "cursor", "antigravity", "vscode", "claude-desktop", "windsurf", "gemini-cli", "codex", "http"):
            self.assertIn(a, sn)
        self.assertEqual(sn["vscode"]["content"]["servers"]["devctl"]["type"], "stdio")
        self.assertTrue(sn["antigravity"]["path"].endswith(".gemini/antigravity/mcp_config.json"))
        cur = Path(sn["cursor"]["path"])
        cur.parent.mkdir(parents=True)
        cur.write_text(json.dumps({"mcpServers": {"other": {"command": "x"}}}))
        agents.install(cfg, "cursor")
        data = json.loads(cur.read_text())
        self.assertEqual(set(data["mcpServers"]), {"other", "devctl"})
        self.assertTrue(Path(str(cur) + ".bak").exists())
        self.assertTrue(data["mcpServers"]["devctl"]["command"].endswith("bin/devctl-mcp"))
        agents.install(cfg, "codex")
        self.assertIn("already", agents.install(cfg, "codex"))
        self.assertEqual(Path(sn["codex"]["path"]).read_text().count("[mcp_servers.devctl]"), 1)
        files = agents.write_files(cfg, self.tmp / "out")
        self.assertEqual(len(files), len(sn))
        for f in files:
            if f.endswith(".json"):
                json.loads(Path(f).read_text())

    def test_config_edits_logs_and_diagnose(self):
        from devctl import agentops, compose, tools
        cfg = self.config.load()
        with self.assertRaises(ValueError):
            agentops.config_set(cfg, "services", "x", {"kind": "jar", "evil": 1})
        with self.assertRaises(ValueError):
            agentops.config_set(cfg, "stacks", "s", {"services": ["missing"]})
        agentops.config_set(cfg, "services", "billing", {"kind": "war", "repo": "orders", "context": "billing"})
        agentops.config_set(self.config.load(), "stacks", "all", {"services": ["orders-api", "billing"]})
        self.assertEqual(self.config.load()["stacks"]["all"], ["orders-api", "billing"])
        agentops.config_remove(self.config.load(), "stacks", "all")
        compose.log_dir().mkdir(parents=True)
        (compose.log_dir() / "orders-api.log").write_text("start\nok\nERROR boom at Foo.java:1\nCaused by: x\nfine\n")
        r = agentops.search_logs(self.config.load(), "orders-api", "error|caused", context=1)
        self.assertEqual(r["matches"], 2)
        d = agentops.diagnose(self.config.load(), "orders-api")
        self.assertIn("ERROR boom at Foo.java:1", d["recent_errors"])
        self.assertTrue(any("not deployed" in h for h in d["hints"]))
        text, err = tools.call("wait_healthy", {"service": "orders-api", "timeout_seconds": 1})
        self.assertFalse(err)
        self.assertFalse(json.loads(text)["healthy"])


# ---------------------------------------------------------------- assistant

class FakeClient:
    """Stands in for anthropic.Anthropic(); replays scripted responses and records requests."""

    def __init__(self, responses):
        self.responses, self.requests = list(responses), []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.requests.append(kw)
        stop, blocks = self.responses.pop(0)
        content = [SimpleNamespace(type=b[0], text=b[1]) if b[0] == "text" else
                   SimpleNamespace(type="tool_use", id=b[1], name=b[2], input=b[3]) for b in blocks]
        return SimpleNamespace(stop_reason=stop, content=content)


class AssistantTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.config = fresh(self.tmp)
        from devctl import assistant
        self.a = assistant

    def test_read_tools_run_writes_wait_for_approval(self):
        cfg = self.config.load()
        fc = FakeClient([
            ("tool_use", [("text", "Checking."), ("tool_use", "t1", "list_repos", {})]),
            ("tool_use", [("tool_use", "t2", "build", {"repo": "orders", "branch": "main"})]),
            ("end_turn", [("text", "Build started.")]),
        ])
        r = self.a.chat(cfg, None, "build orders main", fc)
        kinds = [e["type"] for e in r["events"]]
        self.assertEqual(kinds, ["text", "tool", "result", "pending"])
        self.assertFalse(r["done"])
        req = fc.requests[0]
        self.assertEqual(req["model"], "claude-opus-5-5")
        self.assertEqual(req["fallbacks"], "default")
        self.assertEqual(req["betas"], ["server-side-fallback-2026-07-01"])
        self.assertEqual(req["output_config"], {"effort": "medium"})
        self.assertEqual(len(req["tools"]), len(self.a.tools.TOOLS))
        # a new message is refused while an action is pending
        self.assertEqual(self.a.chat(cfg, r["session"], "hi", fc)["events"][0]["type"], "error")
        r2 = self.a.decide(cfg, r["session"], False, fc)
        self.assertTrue(r2["done"])
        self.assertEqual(r2["events"][0]["text"], "declined by the developer")
        sess = self.a._SESSIONS[r["session"]]["messages"]
        self.assertEqual([m["role"] for m in sess], ["user", "assistant", "user", "assistant", "user", "assistant"])
        self.assertTrue(sess[4]["content"][0]["is_error"])

    def test_refusal_and_errors_keep_history_valid(self):
        cfg = self.config.load()
        r = self.a.chat(cfg, None, "x", FakeClient([("refusal", [])]))
        self.assertIn("declined", r["events"][0]["text"])
        self.assertEqual(self.a._SESSIONS[r["session"]]["messages"], [])

        class Boom(FakeClient):
            def _create(self, **kw):
                raise RuntimeError("401 auth")
        r = self.a.chat(cfg, None, "x", Boom([]))
        self.assertIn("401", r["events"][0]["text"])
        self.assertEqual(self.a._SESSIONS[r["session"]]["messages"], [])

    @unittest.skipUnless(__import__("importlib").util.find_spec("anthropic"), "anthropic SDK not installed")
    def test_real_sdk_against_fake_messages_api(self):
        import anthropic
        seen = []
        replies = [
            {"content": [{"type": "tool_use", "id": "toolu_1", "name": "status", "input": {}}], "stop_reason": "tool_use"},
            {"content": [{"type": "text", "text": "All quiet."}], "stop_reason": "end_turn"},
        ]

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append((self.path, dict(self.headers), body))
                msg = {"id": "msg_1", "type": "message", "role": "assistant", "model": body["model"], "stop_sequence": None,
                       "usage": {"input_tokens": 1, "output_tokens": 1}, **replies[len(seen) - 1]}
                out = json.dumps(msg).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass
        srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            client = anthropic.Anthropic(api_key="test", base_url=f"http://127.0.0.1:{srv.server_address[1]}", max_retries=0)
            r = self.a.chat(self.config.load(), None, "how are my services?", client)
        finally:
            srv.shutdown()
        self.assertEqual([e["type"] for e in r["events"]], ["tool", "result", "text"], r["events"])
        self.assertEqual(r["events"][-1]["text"], "All quiet.")
        path, headers, body = seen[0]
        self.assertTrue(path.startswith("/v1/messages"))
        self.assertIn("server-side-fallback-2026-07-01", headers.get("anthropic-beta", headers.get("Anthropic-Beta", "")))
        self.assertEqual(body["fallbacks"], "default")
        self.assertEqual(body["cache_control"], {"type": "ephemeral"})
        second = seen[1][2]["messages"]
        self.assertEqual(second[1]["content"][0]["type"], "tool_use")  # SDK objects round-trip unchanged
        self.assertEqual(second[2]["content"][0]["tool_use_id"], "toolu_1")


if __name__ == "__main__":
    unittest.main()
