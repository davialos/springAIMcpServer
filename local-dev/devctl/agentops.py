"""Goal-level operations for AI agents: one call instead of a polling loop.

ship = build -> wait -> deploy -> wait healthy; diagnose = everything worth knowing about one service in one answer;
plus log search and safe, validated edits of the services/repos/stacks configuration.
"""
from __future__ import annotations

import re
import time
from pathlib import Path

from . import builds, compose, config, health, jfr, loadrun, metrics, ops, pc

ERR = re.compile(r"(?i)\b(error|exception|fatal|severe|caused by|failed)\b")


def wait_build(cfg: dict, build_id: str, timeout_seconds: int = 600) -> dict:
    end = time.time() + max(1, min(int(timeout_seconds), 3600))
    while True:
        m = builds.read_meta(cfg, build_id)
        if m["status"] != builds.RUNNING or time.time() >= end:
            break
        time.sleep(2)
    out = {**m, "log_tail": builds.read_log(cfg, build_id)["text"][-4000:]}
    if m["status"] == builds.RUNNING:
        out["note"] = "still running - call wait_build again"
    return out


def wait_healthy(cfg: dict, service: str, timeout_seconds: int = 180) -> dict:
    s = cfg["services"].get(service)
    if not s:
        raise ValueError(f"unknown service: {service}")
    h_cfg = compose.service_health(service, s, cfg)
    if not h_cfg:
        return {"service": service, "healthy": None, "detail": "no health check configured"}
    end, h = time.time() + max(1, min(int(timeout_seconds), 1800)), {}
    while time.time() < end:
        h = health.check(h_cfg)
        if h.get("ok"):
            return {"service": service, "healthy": True, **h}
        time.sleep(3)
    return {"service": service, "healthy": False, **h, "log_errors": error_lines(cfg, service, 20)}


def ship(cfg: dict, repo: str, branch: str, service: str, timeout_seconds: int = 900) -> dict:
    """Build repo@branch, deploy the artifact to the service, wait until it is healthy."""
    if service not in cfg["services"]:
        raise ValueError(f"unknown service: {service}")
    t0 = time.time()
    b = builds.start(cfg, repo, branch)
    res = {"build_id": b["id"], "steps": []}
    m = wait_build(cfg, b["id"], timeout_seconds)
    res["steps"].append({"build": m["status"], "commit": m.get("commit"), "artifacts": m.get("artifacts")})
    if m["status"] != builds.SUCCESS:
        res.update(ok=False, stage="build", log_tail=m["log_tail"], note=m.get("note"))
        return res
    res["steps"].append({"deploy": ops.deploy(cfg, service, b["id"])})
    left = max(30, int(timeout_seconds - (time.time() - t0)))
    h = wait_healthy(cfg, service, left)
    res["steps"].append({"health": h})
    res.update(ok=bool(h.get("healthy")) or h.get("healthy") is None, stage="done" if h.get("healthy") is not False else "health",
               seconds=round(time.time() - t0))
    return res


def _log_file(cfg: dict, name: str) -> Path | None:
    s = cfg["services"].get(name, {})
    if s.get("kind") == "war" or name in (compose.JBOSS, "jboss-server"):
        p = compose.jboss_base(cfg) / "log" / "server.log"
        return p if p.exists() else None
    p = compose.log_dir() / f"{name}.log"
    return p if p.exists() else None


def _tail(p: Path, max_bytes: int = 400_000) -> list:
    with open(p, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        f.seek(max(0, size - max_bytes))
        return f.read().decode("utf-8", "replace").splitlines()


def read_logs(cfg: dict, name: str, lines: int = 200) -> str:
    p = _log_file(cfg, name)
    if p:
        return "\n".join(_tail(p)[-int(lines):])
    return pc.logs(cfg, name, lines)  # falls back to process-compose (raises if not running)


def search_logs(cfg: dict, name: str, pattern: str, lines: int = 60, context: int = 0) -> dict:
    p = _log_file(cfg, name)
    if not p:
        raise ValueError(f"no log file for {name} (logs are written once it has run under process-compose)")
    try:
        rx = re.compile(pattern, re.I)
    except re.error as e:
        raise ValueError(f"bad regex: {e}")
    src = _tail(p, 4_000_000)
    hits = []
    for i, ln in enumerate(src):
        if rx.search(ln):
            hits.append("\n".join(src[max(0, i - context): i + context + 1]) if context else ln)
    return {"name": name, "file": str(p), "matches": len(hits), "lines": hits[-int(lines):]}


def error_lines(cfg: dict, name: str, n: int = 30) -> list:
    p = _log_file(cfg, name)
    if not p:
        return []
    return [ln for ln in _tail(p) if ERR.search(ln)][-n:]


def diagnose(cfg: dict, service: str) -> dict:
    """One-call report: process + health + deployment + live metrics + recent errors + latest build/load run/JFR verdict."""
    s = cfg["services"].get(service)
    if not s:
        raise ValueError(f"unknown service: {service} (known: {', '.join(cfg['services']) or 'none'})")
    st = ops.status(cfg, with_health=True)["services"][service]
    out = {"service": service, "kind": s.get("kind", "jar"), "process": st["process"], "health": st["health"],
           "deployed": st["deployed"], "base_url": compose.service_base_url(service, s, cfg)}
    try:
        snap = metrics.snapshot(cfg, service, 1.5)
        out["metrics"] = {"source": snap["source"], "now": snap["now"], "busiest_endpoints": snap["endpoints"][:5]}
    except Exception as e:
        out["metrics"] = {"error": str(e)}
    out["recent_errors"] = error_lines(cfg, service, 25)
    if s.get("repo"):
        last = builds.list_builds(cfg, 1, s["repo"])
        if last:
            b = last[0]
            out["last_build"] = {k: b.get(k) for k in ("id", "branch", "status", "commit", "error")}
    runs = [r for r in loadrun.list_runs(cfg, 20) if r.get("service") == service]
    if runs:
        r = runs[0]
        out["last_load_run"] = {"id": r["id"], "status": r["status"], "k6": r.get("k6"), "jfr": (r.get("jfr") or {}).get("analysis")}
    recs = [x for x in jfr.list_recordings(cfg, service) if x["analysis"].get("status") == "success"]
    if recs:
        out["last_profile"] = {"id": recs[0]["id"], **{k: recs[0]["analysis"].get(k) for k in ("health", "score", "headline")}}
    hints = []
    if not st["deployed"] and s.get("kind", "jar") != "command":
        hints.append("not deployed: build + deploy (tool ship) first")
    if st["health"] and st["health"].get("ok") is False:
        hints.append("health check failing: look at recent_errors / logs")
    if out["metrics"].get("source") in ("probe", None):
        hints.append("no actuator metrics: run perf_readiness for the minimal Spring change")
    out["hints"] = hints
    return out


# ---------------------------------------------------------------- configuration edits (validated)

SERVICE_KEYS = {"kind", "repo", "artifact", "port", "base_url", "health", "env", "args", "java_opts", "java_home",
                "depends_on", "context", "cmd", "cwd", "metrics", "jfr", "needs_jboss"}
REPO_KEYS = {"build_cmd", "artifact_globs", "java_home", "in_place", "env"}
NAME = re.compile(r"[A-Za-z0-9._-]{1,60}")


def config_set(cfg: dict, section: str, name: str, spec: dict) -> dict:
    """Create or replace services.<name>, repos.<name> or stacks.<name> (a list of services)."""
    if section not in ("services", "repos", "stacks"):
        raise ValueError("section must be services, repos or stacks")
    if not NAME.fullmatch(name):
        raise ValueError("name: letters, digits, . _ - only")
    if section == "stacks":
        members = spec.get("services") if isinstance(spec, dict) else spec
        if not isinstance(members, list) or not all(m in cfg["services"] for m in members):
            raise ValueError("stacks.<name> must be {services: [known service names]}")
        cfg["stacks"][name] = members
    else:
        allowed = SERVICE_KEYS if section == "services" else REPO_KEYS
        bad = set(spec) - allowed
        if bad:
            raise ValueError(f"unknown keys {sorted(bad)}; allowed: {sorted(allowed)}")
        if section == "services" and spec.get("kind", "jar") not in ("jar", "war", "command"):
            raise ValueError("kind must be jar, war or command")
        cfg[section][name] = spec
    config.save(cfg)
    return {"saved": f"{section}.{name}", "value": cfg[section][name]}


def config_remove(cfg: dict, section: str, name: str) -> dict:
    if section not in ("services", "repos", "stacks", "loadtests"):
        raise ValueError("section must be services, repos, stacks or loadtests")
    if name not in cfg.get(section, {}):
        raise ValueError(f"{section}.{name} does not exist")
    del cfg[section][name]
    config.save(cfg)
    return {"removed": f"{section}.{name}"}


def config_set_workspace(cfg: dict, workspace: str) -> dict:
    p = Path(workspace).expanduser()
    if not p.is_dir():
        raise ValueError(f"not a directory: {p}")
    cfg["workspace"] = str(p)
    config.save(cfg)
    return {"workspace": str(p)}
