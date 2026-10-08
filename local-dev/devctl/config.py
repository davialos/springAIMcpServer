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
    "infra": {"enabled": ["prometheus", "loki", "grafana"], "prometheus_scrape_path": "/actuator/prometheus",
              "loki_url": "http://localhost:3100", "ship_logs": True},
    "loadtest_dir": "~/localdev/loadtests",  # k6 scripts live here (relative script paths resolve against it)
    "loadtests": {},                          # name -> {script, vus, duration, env}
}


def _merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in over.items():
        out[k] = _merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) and k not in ("repos", "services", "stacks", "loadtests") else v
    return out


def load() -> dict:
    cfg = copy.deepcopy(DEFAULTS)
    if CONFIG_FILE.exists():
        cfg = _merge(cfg, json.loads(CONFIG_FILE.read_text()))
    return cfg


def save(cfg: dict) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2) + "\n")


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
