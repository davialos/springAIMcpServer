"""MCP client configuration for coding agents: generates the exact JSON/TOML/commands for this machine.

stdio (command + args) is the default because every agent supports it and it needs no running server; the HTTP
endpoint (dashboard ``/mcp``) is offered for agents that prefer a URL.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

from . import config

LOCAL_DEV = Path(__file__).resolve().parent.parent
MCP_BIN = LOCAL_DEV / "bin" / "devctl-mcp"
NAME = "devctl"


def http_url(cfg: dict) -> str:
    return f"http://127.0.0.1:{cfg['dashboard_port']}/mcp"


def _stdio() -> dict:
    return {"command": str(MCP_BIN), "args": []}


def snippets(cfg: dict) -> dict:
    """agent -> {path, format, content, note}"""
    std, url = _stdio(), http_url(cfg)
    home = Path.home()
    out = {
        "claude-code": {"path": "(command)", "format": "shell",
                        "content": f"claude mcp add --scope user {NAME} -- {MCP_BIN}",
                        "note": f"or HTTP: claude mcp add --scope user --transport http {NAME} {url}"},
        "claude-desktop": {"path": str(home / "Library/Application Support/Claude/claude_desktop_config.json"), "format": "json",
                           "content": {"mcpServers": {NAME: std}}},
        "cursor": {"path": str(home / ".cursor/mcp.json"), "format": "json", "content": {"mcpServers": {NAME: std}},
                   "note": f"or HTTP: {{\"mcpServers\": {{\"{NAME}\": {{\"url\": \"{url}\"}}}}}}"},
        "antigravity": {"path": str(home / ".gemini/antigravity/mcp_config.json"), "format": "json",
                        "content": {"mcpServers": {NAME: std}},
                        "note": "open it from Antigravity: Agent panel ... > MCP Servers > Manage MCP Servers > View raw config "
                                f"(HTTP form uses \"serverUrl\": \"{url}\")"},
        "vscode": {"path": "(user) MCP: Open User Configuration, or .vscode/mcp.json", "format": "json",
                   "content": {"servers": {NAME: {"type": "stdio", **std}}}},
        "windsurf": {"path": str(home / ".codeium/windsurf/mcp_config.json"), "format": "json", "content": {"mcpServers": {NAME: std}}},
        "gemini-cli": {"path": str(home / ".gemini/settings.json"), "format": "json", "content": {"mcpServers": {NAME: std}}},
        "codex": {"path": str(home / ".codex/config.toml"), "format": "toml",
                  "content": f'[mcp_servers.{NAME}]\ncommand = "{MCP_BIN}"\nargs = []\n'},
        "http": {"path": "(any agent that takes a URL)", "format": "url", "content": url,
                 "note": "needs the dashboard running (devctl dashboard start); loopback only"},
    }
    return out


def render(snip: dict) -> str:
    c = snip["content"]
    return json.dumps(c, indent=2) if isinstance(c, dict) else str(c)


def install(cfg: dict, agent: str) -> str:
    """Merge the devctl entry into the agent's config file (keeps every other server; writes a .bak first)."""
    sn = snippets(cfg).get(agent)
    if not sn:
        raise ValueError(f"unknown agent {agent}; one of {', '.join(snippets(cfg))}")
    if agent == "claude-code":
        if not shutil.which("claude"):
            raise ValueError("claude CLI not found - run the command shown by `devctl agent-setup` yourself")
        subprocess.run(["claude", "mcp", "remove", "--scope", "user", NAME], capture_output=True)
        r = subprocess.run(["claude", "mcp", "add", "--scope", "user", NAME, "--", str(MCP_BIN)], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip() or r.stdout.strip())
        return "added to Claude Code (user scope)"
    if sn["format"] == "toml":
        p = Path(sn["path"])
        text = p.read_text() if p.exists() else ""
        if f"[mcp_servers.{NAME}]" in text:
            return f"{p} already has [mcp_servers.{NAME}] - left unchanged"
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.exists():
            shutil.copy(p, str(p) + ".bak")
        p.write_text(text + ("\n" if text and not text.endswith("\n") else "") + sn["content"])
        return f"updated {p}"
    if sn["format"] != "json" or sn["path"].startswith("("):
        raise ValueError(f"{agent}: add it by hand - see `devctl agent-setup {agent}`")
    p = Path(sn["path"])
    data = {}
    if p.exists():
        try:
            data = json.loads(p.read_text() or "{}")
        except ValueError:
            raise ValueError(f"{p} is not valid JSON - fix it first (nothing changed)")
        shutil.copy(p, str(p) + ".bak")
    key = next(iter(sn["content"]))
    data.setdefault(key, {})[NAME] = sn["content"][key][NAME]
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2) + "\n")
    return f"updated {p}"


def write_files(cfg: dict, out_dir: Path) -> list:
    """Write ready-to-copy files for every agent into out_dir (git-ignored)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for agent, sn in snippets(cfg).items():
        ext = {"json": "json", "toml": "toml", "shell": "sh", "url": "txt"}[sn["format"]]
        f = out_dir / f"{agent}.{ext}"
        f.write_text(render(sn) + "\n")
        written.append(str(f))
    return written
