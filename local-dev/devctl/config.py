"""Configuration and on-disk layout. State lives under LOCALDEV_HOME (default ~/.localdev)."""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path

HOME = Path(os.environ.get("LOCALDEV_HOME", "~/.localdev")).expanduser()
CONFIG_FILE = HOME / "config.json"
STATE_FILE = HOME / "state.json"
PC_FILE = HOME / "process-compose.yaml"  # JSON is valid YAML; written by compose.py

DEFAULTS = {
    "workspace": "~/work",                 # folder whose sub-folders are git repositories
    "builds_dir": "~/localdev",            # build outputs, worktrees, deployed artifacts, jboss base dir
    "dashboard_port": 8765,
    "process_compose_port": 8099,
    "java_home": "auto:21",                # path, or "auto:<major>" (macOS /usr/libexec/java_home)
    "jboss": {"home": "", "port_offset": 0, "java_opts": "-Xms256m -Xmx1g", "java_home": "", "always_on": False},
    "repos": {},     # name -> {build_cmd, artifact_globs, java_home, in_place, env}
    "services": {},  # name -> see config.example.json
    "stacks": {},    # name -> [service, ...]
    "infra": {"enabled": ["prometheus", "loki", "grafana"], "prometheus_scrape_path": "/actuator/prometheus"},
    # CEL faker / flow studio (spring-ai-mcp-server-common-celfaker, ADR-0030): a built-in service of this repo checkout
    "celfaker": {"enabled": True, "port": 8110, "autostart": True, "java_home": "auto:25", "repo_root": ""},
}

REPO_ROOT = Path(__file__).resolve().parents[2]  # local-dev/devctl/config.py -> the checkout that contains scripts/celfaker.sh


def _with_builtins(cfg: dict) -> dict:
    """Adds the CEL faker as a ``command`` service unless disabled, the checkout lacks it, or you defined ``services.celfaker``."""
    fk = cfg.get("celfaker") or {}
    root = Path(fk.get("repo_root") or REPO_ROOT).expanduser()
    if fk.get("enabled", True) and "celfaker" not in cfg["services"] and (root / "scripts" / "celfaker.sh").exists():
        port = int(fk.get("port", 8110))
        cfg["services"]["celfaker"] = {
            "kind": "command", "builtin": "celfaker", "port": port, "cwd": str(root), "cmd": "", "needs_build_tools": True,
            "java_home": fk.get("java_home", "auto:25"), "autostart": bool(fk.get("autostart", True)),
            "health": {"url": f"http://127.0.0.1:{port}/api/example"}, "open_url": f"http://localhost:{port}/",
        }
    return cfg


def _merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in over.items():
        out[k] = _merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) and k not in ("repos", "services", "stacks") else v
    return out


def load() -> dict:
    cfg = copy.deepcopy(DEFAULTS)
    if CONFIG_FILE.exists():
        cfg = _merge(cfg, json.loads(CONFIG_FILE.read_text()))
    return _with_builtins(cfg)


def save(cfg: dict) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    out = {**cfg, "services": {k: v for k, v in cfg["services"].items() if not v.get("builtin")}}  # built-ins are derived, never persisted
    CONFIG_FILE.write_text(json.dumps(out, indent=2) + "\n")


def path(cfg: dict, key: str) -> Path:
    return Path(str(cfg[key])).expanduser()


def builds_root(cfg: dict) -> Path:
    return path(cfg, "builds_dir")


def state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {"deployed": {}}


def save_state(st: dict) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(st, indent=2))
    tmp.replace(STATE_FILE)
