"""One-command setup and the background dashboard (which also serves MCP over HTTP).

    devctl setup [--workspace DIR] [--with-ai] [--install claude-code,cursor,...] [--no-start]
    devctl dashboard start | stop | status    background dashboard (pid + log in $LOCALDEV_HOME)
    devctl autostart install | uninstall       macOS launchd agent (dashboard at login)
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from . import agents, config, ops

LOCAL_DEV = Path(__file__).resolve().parent.parent
REPO_ROOT = LOCAL_DEV.parent
VENV = LOCAL_DEV / ".venv"
PID = config.HOME / "dashboard.pid"
LOG = config.HOME / "dashboard.log"
PLIST = Path.home() / "Library/LaunchAgents/dev.devctl.dashboard.plist"


def _say(msg: str = "") -> None:
    print(msg, flush=True)


def ensure_config(workspace: str = "") -> dict:
    cfg = config.load()
    if not config.CONFIG_FILE.exists():
        ws = Path(workspace).expanduser() if workspace else REPO_ROOT.parent
        cfg["workspace"] = str(ws)
        config.save({"workspace": cfg["workspace"], "builds_dir": cfg["builds_dir"], "dashboard_port": cfg["dashboard_port"],
                     "process_compose_port": cfg["process_compose_port"], "java_home": cfg["java_home"], "jboss": cfg["jboss"],
                     "repos": {}, "services": {}, "stacks": {}, "loadtests": {}, "infra": cfg["infra"],
                     "ai": {"model": "claude-opus-5-5", "effort": "medium", "confirm_writes": True}})
        _say(f"  created {config.CONFIG_FILE} (workspace {ws}) - services are added in the dashboard, by an agent "
             f"(prompt 'onboard-project') or by editing the file; example: local-dev/config.example.json")
    elif workspace:
        cfg["workspace"] = str(Path(workspace).expanduser())
        config.save(cfg)
        _say(f"  workspace set to {cfg['workspace']}")
    return config.load()


def capture_env() -> Path:
    """Save PATH / JAVA_HOME / MAVEN_HOME / DOCKER_HOST of this shell for agents and launchd, which start with a bare PATH."""
    import shlex
    f = config.HOME / "env.sh"
    config.HOME.mkdir(parents=True, exist_ok=True)
    lines = ["# written by `devctl setup` - the environment of the shell setup ran in; rerun setup to refresh"]
    for k in ("PATH", "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "GRADLE_HOME", "DOCKER_HOST", "K6_BIN"):
        if os.environ.get(k):
            lines.append(f"export {k}={shlex.quote(os.environ[k])}")
    f.write_text("\n".join(lines) + "\n")
    return f


def _best_python() -> str | None:
    for name in ("python3.14", "python3.13", "python3.12", "python3.11", "python3.10", "python3"):
        exe = shutil.which(name)
        if not exe:
            continue
        r = subprocess.run([exe, "-c", "import sys;print(sys.version_info[:2] >= (3, 10))"], capture_output=True, text=True)
        if r.stdout.strip() == "True":
            return exe
    return None


def setup_ai() -> bool:
    if (VENV / "bin" / "python").exists():
        r = subprocess.run([str(VENV / "bin/python"), "-c", "import anthropic"], capture_output=True)
        if r.returncode == 0:
            _say("  AI: local-dev/.venv already has the anthropic SDK")
            return True
    py = _best_python()
    if not py:
        _say("  AI: needs Python >= 3.10 for the anthropic SDK - `brew install python@3.12`, then rerun with --with-ai")
        return False
    _say(f"  AI: creating local-dev/.venv with {py} and installing the anthropic SDK ...")
    subprocess.run([py, "-m", "venv", str(VENV)], check=True)
    r = subprocess.run([str(VENV / "bin/python"), "-m", "pip", "install", "-q", "--upgrade", "anthropic"])
    if r.returncode != 0:
        _say("  AI: pip install anthropic failed - see the output above")
        return False
    _say("  AI: ready. Credentials: export ANTHROPIC_API_KEY=... before `devctl dashboard start` (or `ant auth login`).")
    return True


def _python() -> str:
    v = VENV / "bin" / "python"
    return str(v) if v.exists() else sys.executable


def running_pid() -> int | None:
    try:
        pid = int(PID.read_text().strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def _answers(cfg: dict) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{cfg['dashboard_port']}/api/assistant/status", timeout=1)
        return True
    except Exception:
        return False


def start(cfg: dict) -> str:
    if running_pid() and _answers(cfg):
        return f"dashboard already running (pid {running_pid()}): http://127.0.0.1:{cfg['dashboard_port']}"
    if _answers(cfg):
        return f"something already answers on :{cfg['dashboard_port']} (autostart?) - http://127.0.0.1:{cfg['dashboard_port']}"
    config.HOME.mkdir(parents=True, exist_ok=True)
    with open(LOG, "ab") as log:
        p = subprocess.Popen([_python(), "-m", "devctl", "dashboard"], stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True, cwd=str(LOCAL_DEV), env={**os.environ, "PYTHONPATH": str(LOCAL_DEV)})
    PID.write_text(str(p.pid))
    for _ in range(40):
        if _answers(cfg):
            return f"dashboard started (pid {p.pid}): http://127.0.0.1:{cfg['dashboard_port']}  log: {LOG}"
        if p.poll() is not None:
            break
        time.sleep(0.25)
    raise RuntimeError(f"dashboard did not start - see {LOG}")


def stop() -> str:
    pid = running_pid()
    if not pid:
        return "dashboard not running (started by devctl dashboard start)"
    os.kill(pid, signal.SIGTERM)
    PID.unlink(missing_ok=True)
    return f"stopped dashboard (pid {pid})"


def daemon_status(cfg: dict) -> str:
    pid = running_pid()
    up = _answers(cfg)
    return (f"dashboard: {'running' if up else 'not answering'}{f' (pid {pid})' if pid else ''} - "
            f"http://127.0.0.1:{cfg['dashboard_port']}  MCP http: {agents.http_url(cfg)}  MCP stdio: {agents.MCP_BIN}")


def autostart(cfg: dict, action: str) -> str:
    if platform.system() != "Darwin":
        return "autostart uses macOS launchd; on Linux create a systemd user unit running: " \
               f"{LOCAL_DEV / 'bin/devctl'} dashboard"
    if action == "uninstall":
        subprocess.run(["launchctl", "unload", "-w", str(PLIST)], capture_output=True)
        PLIST.unlink(missing_ok=True)
        return "autostart removed"
    stop()
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "LOCALDEV_HOME": str(config.HOME)}
    plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>dev.devctl.dashboard</string>
  <key>ProgramArguments</key><array><string>{LOCAL_DEV / 'bin/devctl'}</string><string>dashboard</string></array>
  <key>EnvironmentVariables</key><dict>{''.join(f'<key>{k}</key><string>{v}</string>' for k, v in env.items())}</dict>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>{LOG}</string><key>StandardErrorPath</key><string>{LOG}</string>
</dict></plist>
"""
    PLIST.write_text(plist)
    subprocess.run(["launchctl", "unload", "-w", str(PLIST)], capture_output=True)
    r = subprocess.run(["launchctl", "load", "-w", str(PLIST)], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return (f"autostart installed ({PLIST}); the dashboard starts at login. For the AI assistant under launchd use an "
            f"`ant auth login` profile (no API key is written to the plist).")


def mcp_selftest() -> dict:
    """Start the stdio server like an agent would and run initialize + tools/list + one read-only call."""
    p = subprocess.Popen([str(agents.MCP_BIN)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    msgs = [{"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "selftest", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "list_repos", "arguments": {}}}]
    out, err = p.communicate("\n".join(json.dumps(m) for m in msgs) + "\n", timeout=60)
    res = [json.loads(line) for line in out.splitlines() if line.strip()]
    if len(res) != 3:
        raise RuntimeError(f"unexpected MCP output: {out[:500]} {err[:500]}")
    repos_found = json.loads(res[2]["result"]["content"][0]["text"]) if not res[2]["result"]["isError"] else []
    return {"server": res[0]["result"]["serverInfo"], "protocol": res[0]["result"]["protocolVersion"],
            "tools": len(res[1]["result"]["tools"]), "repos_visible": len(repos_found)}


def run_setup(workspace: str, with_ai: bool, install: list, no_start: bool) -> int:
    _say("devctl setup")
    _say("1. configuration")
    cfg = ensure_config(workspace)
    _say(f"  saved this shell's PATH/JAVA_HOME to {capture_env()} (agents launched from the Dock get a bare PATH)")
    _say("2. prerequisites")
    for name, ok, hint in ops.doctor(cfg):
        _say(f"  [{'ok' if ok else '--'}] {name}" + ("" if ok else f"   -> {hint}"))
    _say("3. AI assistant")
    if with_ai:
        setup_ai()
    else:
        _say("  skipped (add --with-ai for the dashboard assistant; MCP needs nothing extra)")
    _say("4. MCP self-test")
    t = mcp_selftest()
    _say(f"  {t['server']['name']} {t['server']['version']}: protocol {t['protocol']}, {t['tools']} tools, {t['repos_visible']} repos visible")
    _say("5. coding agents")
    files = agents.write_files(cfg, config.HOME / "agents")
    _say(f"  ready-to-copy configs: {config.HOME / 'agents'}/ ({len(files)} files)")
    for a in install:
        try:
            _say(f"  {a}: {agents.install(cfg, a)}")
        except Exception as e:
            _say(f"  {a}: {e}")
    _say("6. dashboard")
    if not no_start:
        try:
            _say("  " + start(cfg))
        except RuntimeError as e:
            _say(f"  {e}")
    _say(f"""
Done. Connect your coding agent (details: local-dev/docs/mcp-agents.md):
  Claude Code   claude mcp add --scope user devctl -- {agents.MCP_BIN}
  Cursor / Antigravity / Windsurf / Gemini CLI / Claude Desktop:  {{"mcpServers": {{"devctl": {{"command": "{agents.MCP_BIN}"}}}}}}
  URL-based     {agents.http_url(cfg)}   (while the dashboard runs)
Then ask it e.g. "use devctl: what services do I have and are they healthy?"
""")
    return 0
