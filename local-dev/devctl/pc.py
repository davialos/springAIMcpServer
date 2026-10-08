"""Thin wrapper over the process-compose CLI (talks to the running project on a fixed port)."""
from __future__ import annotations

import json
import shutil
import subprocess

from . import compose, config


class PcError(RuntimeError):
    pass


def available() -> bool:
    return shutil.which("process-compose") is not None


def _run(cfg: dict, *args: str, timeout=30) -> subprocess.CompletedProcess:
    if not available():
        raise PcError("process-compose not found - run `brew install process-compose` (see local-dev/Brewfile)")
    return subprocess.run(["process-compose", *args, "-p", str(cfg["process_compose_port"])],
                          capture_output=True, text=True, timeout=timeout)


def write_project(cfg: dict) -> dict:
    proj = compose.build_project(cfg, config.state())
    config.HOME.mkdir(parents=True, exist_ok=True)
    compose.log_dir().mkdir(parents=True, exist_ok=True)
    config.PC_FILE.write_text(json.dumps(proj, indent=2))  # JSON is valid YAML
    return proj


def is_up(cfg: dict) -> bool:
    try:
        return _run(cfg, "project", "state", timeout=8).returncode == 0
    except Exception:
        return False


def sync(cfg: dict) -> str:
    """Regenerate the project file and apply it: start headless if not running, else live-update."""
    write_project(cfg)
    f = str(config.PC_FILE)
    if is_up(cfg):
        r = _run(cfg, "project", "update", "-f", f, timeout=60)
        if r.returncode != 0:
            raise PcError(r.stderr.strip() or r.stdout.strip())
        return "updated"
    subprocess.Popen(["process-compose", "up", "-f", f, "-p", str(cfg["process_compose_port"]), "-D", "--tui=false"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return "started"


def down(cfg: dict) -> None:
    _run(cfg, "down", timeout=90)


def processes(cfg: dict) -> dict:
    """name -> {status, ready, restarts, pid}; empty when the project is not running."""
    try:
        r = _run(cfg, "process", "list", "-o", "json", timeout=10)
        data = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else []
    except Exception:
        return {}
    return {p["name"]: {"status": p.get("status", "?"), "ready": p.get("is_ready", "-"),
                        "restarts": p.get("restarts", 0), "pid": p.get("pid", 0)} for p in data}


def control(cfg: dict, action: str, name: str) -> str:
    if action not in ("start", "stop", "restart"):
        raise ValueError(action)
    r = _run(cfg, "process", action, name, timeout=60)
    if r.returncode != 0:
        raise PcError(r.stderr.strip() or r.stdout.strip() or f"{action} {name} failed")
    return f"{action} {name}: ok"


def logs(cfg: dict, name: str, lines: int = 200) -> str:
    r = _run(cfg, "process", "logs", name, "-n", str(int(lines)), timeout=15)
    if r.returncode != 0:
        raise PcError(r.stderr.strip() or "no logs (is the project running?)")
    return r.stdout
