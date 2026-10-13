"""Scheduled rollouts: build -> Liquibase migrate -> deploy services -> verify health -> roll back on failure.

A deployment is a record ``~/localdev/deployments/<id>/{meta.json,deploy.log}``. It runs as a detached
``devctl _run-deploy <id>`` (like builds) so it survives dashboard restarts; ``scheduler_loop`` (a process-compose
process) starts the ones whose ``run_at`` has passed. The filesystem is the only shared state.

Rollback order on a failed rollout: services first (previous build redeployed / undeployed), then the database
(``liquibase.sh rollback pre-deploy-<id>``, using the same build's migration snapshot), then re-verify health.
"""
from __future__ import annotations

import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

from . import builds, compose, config, dbrun, health, ops, repos

SCHEDULED, RUNNING, SUCCESS, FAILED, ROLLED_BACK, ROLLBACK_FAILED, CANCELLED = (
    "scheduled", "running", "success", "failed", "rolled_back", "rollback_failed", "cancelled")
ACTIVE = (RUNNING,)


def root(cfg: dict) -> Path:
    return config.builds_root(cfg) / "deployments"


def _dir(cfg: dict, dep_id: str) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", dep_id):
        raise ValueError("bad deployment id")
    return root(cfg) / dep_id


def read(cfg: dict, dep_id: str) -> dict:
    p = _dir(cfg, dep_id) / "meta.json"
    if not p.exists():
        raise ValueError(f"unknown deployment: {dep_id}")
    return json.loads(p.read_text())


def _write(cfg: dict, meta: dict) -> None:
    p = _dir(cfg, meta["id"]) / "meta.json"
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(meta, indent=2))
    tmp.replace(p)


def _alive(pid) -> bool:
    try:
        os.kill(int(pid), 0)
        return True
    except (ProcessLookupError, TypeError, ValueError):
        return False
    except PermissionError:
        return True


def list_all(cfg: dict, limit: int = 50, environment: str = "") -> list:
    d = root(cfg)
    out = []
    if not d.is_dir():
        return out
    for m in sorted(d.iterdir(), reverse=True):
        try:
            meta = json.loads((m / "meta.json").read_text())
        except Exception:
            continue
        if meta["status"] == RUNNING and meta.get("pid") and not _alive(meta["pid"]):
            meta.update(status=FAILED, error="runner process died", finished=time.time())
            _write(cfg, meta)
        if environment and meta["environment"] != environment:
            continue
        out.append(meta)
        if len(out) >= limit:
            break
    return out


def busy(cfg: dict, environment: str) -> str:
    """Id of the running deployment on this environment, else ''."""
    for m in list_all(cfg, 200, environment):
        if m["status"] == RUNNING:
            return m["id"]
    return ""


def parse_time(v) -> float | None:
    if v in (None, "", 0):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    return datetime.fromisoformat(str(v)).timestamp()  # naive = local time (datetime-local input)


def create(cfg: dict, environment: str, repo: str, branch: str, run_at=None, build_id: str = "",
           services: list | None = None, migrate: bool = True, note: str = "") -> dict:
    """Validate and record a deployment (status ``scheduled``). Starts nothing."""
    env = cfg.get("environments", {}).get(environment)
    if env is None:
        raise ValueError(f"unknown environment: {environment}")
    repos.repo_path(cfg, repo)
    if build_id:
        bm = builds.read_meta(cfg, build_id)
        if bm["status"] != builds.SUCCESS or bm["repo"] != repo:
            raise ValueError(f"build {build_id} must be a successful build of {repo}")
        branch = bm["branch"]
    if not re.fullmatch(r"[A-Za-z0-9._/@+-]+", branch or "") or branch.startswith("-"):
        raise ValueError(f"invalid branch name: {branch}")
    allowed = env.get("allowed_branches") or []
    if allowed and not any(fnmatch.fnmatch(branch, g) for g in allowed):
        raise ValueError(f"branch '{branch}' is not allowed on {environment} (allowed: {', '.join(allowed)})")
    env_services = env.get("services", [])
    chosen = services if services is not None else [s for s in env_services if cfg["services"].get(s, {}).get("repo") == repo]
    for s in chosen:
        if s not in env_services:
            raise ValueError(f"service {s} is not part of environment {environment}")
        if cfg["services"].get(s, {}).get("repo") != repo:
            raise ValueError(f"service {s} does not belong to repo {repo}")
    if not chosen and not (migrate and dbrun.db_configured(cfg, environment)):
        raise ValueError(f"nothing to deploy: no services of repo {repo} in environment {environment}")
    when = parse_time(run_at)
    dep_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{re.sub(r'[^A-Za-z0-9._-]+', '_', environment)}-{re.sub(r'[^A-Za-z0-9._-]+', '_', repo)}-{uuid.uuid4().hex[:4]}"
    d = root(cfg) / dep_id
    d.mkdir(parents=True, exist_ok=False)
    meta = {"id": dep_id, "environment": environment, "repo": repo, "branch": branch, "build_id": build_id or None,
            "services": chosen, "migrate": bool(migrate), "status": SCHEDULED, "phase": "waiting", "run_at": when,
            "created": time.time(), "started": None, "finished": None, "steps": [], "previous": {}, "db": {},
            "error": None, "pid": None, "note": note}
    _write(cfg, meta)
    return meta


def cancel(cfg: dict, dep_id: str) -> dict:
    meta = read(cfg, dep_id)
    if meta["status"] != SCHEDULED:
        raise ValueError(f"only scheduled deployments can be cancelled (this one is {meta['status']})")
    meta.update(status=CANCELLED, phase="cancelled", finished=time.time())
    _write(cfg, meta)
    return meta


def start(cfg: dict, dep_id: str) -> dict:
    """Claim and spawn the runner. Refuses when the environment already has a running deployment."""
    meta = read(cfg, dep_id)
    if meta["status"] != SCHEDULED:
        raise ValueError(f"deployment is {meta['status']}, not scheduled")
    other = busy(cfg, meta["environment"])
    if other:
        raise ValueError(f"environment {meta['environment']} is busy with deployment {other}")
    meta.update(status=RUNNING, phase="starting", started=time.time())
    _write(cfg, meta)
    with open(_dir(cfg, dep_id) / "deploy.log", "ab") as log:
        p = subprocess.Popen([sys.executable, "-m", "devctl", "_run-deploy", dep_id], stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True, cwd=str(Path(__file__).resolve().parent.parent),
                             env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent.parent)})
    meta["pid"] = p.pid
    _write(cfg, meta)
    return meta


def read_log(cfg: dict, dep_id: str, offset: int = 0) -> dict:
    read(cfg, dep_id)
    p = _dir(cfg, dep_id) / "deploy.log"
    if not p.exists():
        return {"text": "", "offset": 0}
    with open(p, "rb") as f:
        f.seek(offset)
        data = f.read(200_000)
    return {"text": data.decode("utf-8", "replace"), "offset": offset + len(data)}


# --------------------------------------------------------------------------------------------- runner

class _Run:
    def __init__(self, cfg: dict, meta: dict):
        self.cfg, self.meta = cfg, meta

    def step(self, name: str, phase: str | None = None) -> dict:
        s = {"name": name, "status": "running", "started": time.time(), "finished": None, "detail": ""}
        self.meta["steps"].append(s)
        self.meta["phase"] = phase or name
        _write(self.cfg, self.meta)
        print(f"\n=== {name} ===", flush=True)
        return s

    def done(self, s: dict, status: str = "ok", detail: str = "") -> None:
        s.update(status=status, finished=time.time(), detail=detail)
        _write(self.cfg, self.meta)
        print(f"--- {s['name']}: {status} {detail}", flush=True)


def _wait_build(cfg: dict, build_id: str, timeout: float) -> dict:
    t0 = time.time()
    while time.time() - t0 < timeout:
        m = builds.read_meta(cfg, build_id)
        if m["status"] != builds.RUNNING:
            return m
        time.sleep(2)
    raise RuntimeError(f"build {build_id} did not finish within {int(timeout)}s")


def verify(cfg: dict, services: list, timeout: float, interval: float = 3.0) -> tuple[bool, str]:
    """Poll health of every service until all are up or the timeout passes. Services without a probe are skipped."""
    pending = {n: compose.service_health(n, cfg["services"][n], cfg) for n in services}
    pending = {n: h for n, h in pending.items() if h}
    if not pending:
        return True, "no health probes configured"
    interval = min(interval, max(0.2, timeout / 10))
    t0, last = time.time(), {}
    while True:
        for n in list(pending):
            r = health.check(pending[n])
            last[n] = r.get("detail", "?")
            if r.get("ok"):
                del pending[n]
        if not pending:
            return True, "all services healthy"
        if time.time() - t0 >= timeout:
            return False, "unhealthy after %ds: %s" % (timeout, ", ".join(f"{n} ({last[n]})" for n in pending))
        time.sleep(interval)


def _rollback(r: _Run, meta: dict, env: dict, timeout: float) -> bool:
    cfg = r.cfg
    s = r.step("rollback services", "rolling_back")
    ok, notes = True, []
    for svc in reversed(meta.get("deployed_now", [])):
        prev = meta["previous"].get(svc)
        try:
            if prev and prev.get("build_id"):
                ops.deploy(cfg, svc, prev["build_id"])
                notes.append(f"{svc} -> {prev['branch']}@{prev['commit']}")
            elif svc in config.state().get("deployed", {}):
                ops.undeploy(cfg, svc)
                notes.append(f"{svc} undeployed")
        except Exception as e:
            ok = False
            notes.append(f"{svc}: {e}")
    r.done(s, "ok" if ok else "failed", "; ".join(notes))
    if meta["db"].get("migrated"):
        s = r.step("rollback database", "rolling_back")
        mig = dbrun.migrations_for(cfg, meta["build_id"])
        try:
            with dbrun.locked(cfg, meta["environment"]):
                rc = dbrun.run_script(cfg, meta["environment"], "rollback", meta["db"]["tag"], mig, meta["id"])
        except Exception as e:
            print(f"ERROR: {e}", flush=True)
            rc = 1
        meta["db"]["rolled_back"] = rc == 0
        r.done(s, "ok" if rc == 0 else "failed", f"rollback to {meta['db']['tag']} rc={rc}")
        ok = ok and rc == 0
    if ok and meta["services"]:
        s = r.step("verify after rollback", "rolling_back")
        good, detail = verify(cfg, [x for x in meta["services"] if x in config.state().get("deployed", {})], timeout)
        r.done(s, "ok" if good else "failed", detail)
    return ok


def run(cfg: dict, dep_id: str) -> int:
    """Runner body (stdout is deploy.log). Exit 0 on success."""
    meta = read(cfg, dep_id)
    meta.update(status=RUNNING, pid=os.getpid())
    r = _Run(cfg, meta)
    env = cfg["environments"].get(meta["environment"], {})
    timeout = float(env.get("health_timeout", 120))
    print(f"deployment {dep_id}: {meta['repo']}@{meta['branch']} -> {meta['environment']}", flush=True)
    failed_at = ""
    try:
        # 1. build
        s = r.step("build")
        if meta["build_id"]:
            r.done(s, "ok", f"reusing {meta['build_id']}")
        else:
            b = builds.start(cfg, meta["repo"], meta["branch"])
            meta["build_id"] = b["id"]
            _write(cfg, meta)
            bm = _wait_build(cfg, b["id"], float(cfg.get("build_timeout", 3600)))
            if bm["status"] != builds.SUCCESS:
                raise RuntimeError(f"build {b['id']} failed: {bm.get('error')}")
            r.done(s, "ok", f"{b['id']} @{bm['commit']}")
        bm = builds.read_meta(cfg, meta["build_id"])
        deployed = config.state().get("deployed", {})
        meta["previous"] = {sv: deployed.get(sv) for sv in meta["services"]}
        _write(cfg, meta)
        # 2. database
        if meta["migrate"] and bm.get("migrations") and dbrun.db_configured(cfg, meta["environment"]):
            s = r.step("database migrate", "migrating")
            tag = f"pre-deploy-{dep_id}"
            meta["db"] = {"tag": tag, "migrated": False, "rolled_back": False}
            with dbrun.locked(cfg, meta["environment"]):
                rc = dbrun.run_script(cfg, meta["environment"], "migrate", "", dbrun.migrations_for(cfg, meta["build_id"]), dep_id)
            if rc != 0:  # liquibase.sh already rolled the failed update back to the tag
                meta["db"]["rolled_back"] = True
                failed_at = "database"
                raise RuntimeError(f"liquibase migrate failed (rc={rc}); database rolled back to {tag}")
            meta["db"]["migrated"] = True
            r.done(s, "ok", f"tag {tag}")
        elif meta["migrate"]:
            s = r.step("database migrate")
            r.done(s, "skipped", "no migrations in build or no database configured")
        # 3. deploy
        for svc in meta["services"]:
            s = r.step(f"deploy {svc}", "deploying")
            failed_at = "deploy"
            meta.setdefault("deployed_now", []).append(svc)  # rollback only touches what this run reached
            _write(cfg, meta)
            res = ops.deploy(cfg, svc, meta["build_id"])
            r.done(s, "ok", res["target"])
        # 4. verify
        if meta["services"]:
            s = r.step("verify health", "verifying")
            failed_at = "verify"
            ok, detail = verify(cfg, meta["services"], timeout)
            if not ok:
                r.done(s, "failed", detail)
                raise RuntimeError(detail)
            r.done(s, "ok", detail)
        meta.update(status=SUCCESS, phase="done", finished=time.time())
        _write(cfg, meta)
        print("\nDEPLOYMENT SUCCEEDED", flush=True)
        return 0
    except Exception as e:
        meta["error"] = str(e)
        print(f"\nDEPLOYMENT FAILED ({failed_at or 'build'}): {e}", flush=True)
        for st in meta["steps"]:
            if st["status"] == "running":
                st.update(status="failed", finished=time.time(), detail=str(e))
        if failed_at in ("deploy", "verify") and env.get("auto_rollback", True):
            ok = _rollback(r, meta, env, timeout)
            meta["status"] = ROLLED_BACK if ok else ROLLBACK_FAILED
        elif failed_at in ("deploy", "verify"):
            meta["status"] = FAILED
            print("auto_rollback is off: leaving the failed state in place", flush=True)
        else:
            meta["status"] = FAILED  # build or migration failure: nothing (or only an already-reverted DB) to undo
        meta.update(phase="done", finished=time.time())
        _write(cfg, meta)
        return 1


# ------------------------------------------------------------------------------------------- scheduler

HEARTBEAT = config.HOME / "scheduler.heartbeat"


def tick(cfg: dict, now: float | None = None) -> list:
    """Start every scheduled deployment that is due and whose environment is free. Returns started ids."""
    now = now or time.time()
    started = []
    due = sorted((m for m in list_all(cfg, 500) if m["status"] == SCHEDULED and (m["run_at"] or 0) <= now),
                 key=lambda m: m["run_at"] or 0)
    for m in due:
        try:
            start(cfg, m["id"])
            started.append(m["id"])
        except ValueError:
            continue  # environment busy: stays scheduled, retried next tick
    return started


def scheduler_alive() -> bool:
    try:
        return time.time() - float(HEARTBEAT.read_text()) < 45
    except Exception:
        return False


def scheduler_loop(once: bool = False) -> None:
    while True:
        cfg = config.load()
        config.HOME.mkdir(parents=True, exist_ok=True)
        HEARTBEAT.write_text(str(time.time()))
        try:
            for i in tick(cfg):
                print(f"started deployment {i}", flush=True)
        except Exception as e:
            print(f"scheduler error: {type(e).__name__}: {e}", flush=True)
        if once:
            return
        time.sleep(max(2, int(cfg.get("scheduler", {}).get("poll_seconds", 10))))
