"""Liquibase runs per environment (wraps ``scripts/db/liquibase.sh``).

One owner per fact: DB settings live in ``config.environments.<env>.db``; credentials are resolved here at run
time (env var or ``~/.localdev/secrets.env``) so they never reach config.json, process-compose.yaml or the browser.
Runs are serialised per environment by a lock file; every run writes ``dbjobs/<env>/<ts>-<action>.log`` + ``last.json``.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from . import config

ACTIONS = ("validate", "status", "history", "migrate", "rollback", "rollback-count", "tag-name")
DB_TYPES = ("postgresql", "mysql", "mariadb", "sqlserver", "oracle")
SECRETS = config.HOME / "secrets.env"
REPO_ROOT = Path(__file__).resolve().parent.parent.parent  # the springAIMcpServer checkout


def _env(cfg: dict, name: str) -> dict:
    if name not in cfg.get("environments", {}):
        raise ValueError(f"unknown environment: {name} (known: {', '.join(cfg.get('environments', {})) or 'none'})")
    return cfg["environments"][name]


def db_configured(cfg: dict, name: str) -> bool:
    db = _env(cfg, name).get("db") or {}
    return bool(db.get("url") or db.get("host"))


def load_secrets() -> dict:
    out = {}
    if SECRETS.exists():
        for line in SECRETS.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def _secret(key: str) -> str:
    return os.environ.get(key) or load_secrets().get(key, "")


def db_env(cfg: dict, name: str) -> dict:
    """Environment variables understood by liquibase.sh for this environment (incl. credentials)."""
    e = _env(cfg, name)
    db = e.get("db") or {}
    if not db_configured(cfg, name):
        raise ValueError(f"environment {name} has no database configured")
    env = {"APP_ENV": name}
    for k in ("type", "host", "port", "name", "schema", "url"):
        if db.get(k) not in (None, ""):
            env["DB_" + k.upper()] = str(db[k])
    user = db.get("username") or (_secret(db["username_env"]) if db.get("username_env") else "")
    pw = _secret(db["password_env"]) if db.get("password_env") else ""
    if db.get("password_env") and not pw:
        raise ValueError(f"password for {name} not found: set env var {db['password_env']} or add it to {SECRETS}")
    env["DB_USERNAME"], env["DB_PASSWORD"] = user, pw
    if e.get("contexts"):
        env["LIQUIBASE_CONTEXTS"] = e["contexts"]
    return env


def script(cfg: dict) -> Path:
    return Path(cfg.get("liquibase", {}).get("script") or REPO_ROOT / "scripts" / "db" / "liquibase.sh").expanduser()


def default_migrations(cfg: dict) -> Path:
    return Path(cfg.get("liquibase", {}).get("migrations_dir") or REPO_ROOT / "db-migrations").expanduser()


def migrations_for(cfg: dict, build_id: str = "") -> Path:
    if not build_id:
        return default_migrations(cfg)
    from . import builds
    d = builds.runs_dir(cfg) / build_id / "migrations"
    builds.read_meta(cfg, build_id)
    if not d.is_dir():
        raise ValueError(f"build {build_id} has no migrations folder (set repos.<repo>.migrations_dir)")
    return d


def _validate(action: str, arg: str) -> None:
    if action not in ACTIONS:
        raise ValueError(f"unknown action {action}; one of {', '.join(ACTIONS)}")
    if action == "rollback" and not re.fullmatch(r"[A-Za-z0-9._-]+", arg or ""):
        raise ValueError("rollback needs a tag (letters, digits, . _ -)")
    if action == "rollback-count" and not re.fullmatch(r"[1-9][0-9]{0,3}", arg or ""):
        raise ValueError("rollback-count needs a positive integer")


def jobs_dir(cfg: dict, name: str) -> Path:
    return config.builds_root(cfg) / "dbjobs" / name


def _lock_alive(lk: Path) -> bool:
    try:
        os.kill(int(lk.read_text() or 0), 0)
        return True
    except PermissionError:
        return True
    except (ProcessLookupError, ValueError, FileNotFoundError):
        return False


def busy(cfg: dict, name: str) -> bool:
    lk = jobs_dir(cfg, name) / "lock"
    return lk.exists() and _lock_alive(lk)


@contextlib.contextmanager
def locked(cfg: dict, name: str):
    """Exclusive per-environment lock (O_EXCL file holding the owner pid; stale locks are reclaimed)."""
    d = jobs_dir(cfg, name)
    d.mkdir(parents=True, exist_ok=True)
    lk = d / "lock"
    for attempt in (1, 2):
        try:
            fd = os.open(lk, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode())
            os.close(fd)
            break
        except FileExistsError:
            if _lock_alive(lk) or attempt == 2:
                raise ValueError(f"a database job is already running for {name}")
            lk.unlink(missing_ok=True)  # stale: owner died
    try:
        yield
    finally:
        lk.unlink(missing_ok=True)


def run_script(cfg: dict, name: str, action: str, arg: str = "", migrations: Path | None = None,
               deploy_id: str = "", out=None) -> int:
    """Run one liquibase.sh command, streaming combined output to ``out`` (default stdout). Returns the exit code."""
    _validate(action, arg)
    env = {**os.environ, **db_env(cfg, name), "MIGRATIONS_DIR": str(migrations or default_migrations(cfg))}
    if deploy_id:
        env["DEPLOY_ID"] = deploy_id
    sh = script(cfg)
    if not sh.exists():
        raise ValueError(f"liquibase script not found: {sh}")
    argv = ["bash", str(sh), action] + ([arg] if arg else [])
    out = out or sys.stdout
    with subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as p:
        for line in p.stdout:
            out.write(line)
            out.flush()
        return p.wait()


def request_file(name: str) -> Path:
    return config.HOME / "db" / f"{name}.request.json"


def submit(cfg: dict, name: str, action: str, arg: str = "", build_id: str = "") -> dict:
    """Queue a run (written to the request file) and trigger the ``liquibase-<env>`` process."""
    _validate(action, arg)
    db_env(cfg, name)  # fail fast on missing config/credentials
    migrations_for(cfg, build_id)
    if busy(cfg, name):
        raise ValueError(f"a database job is already running for {name}")
    f = request_file(name)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps({"action": action, "arg": arg, "build_id": build_id, "at": time.time()}))
    from . import pc
    if pc.available() and pc.is_up(cfg):
        pc.sync(cfg)  # make sure liquibase-<env> exists in the running project
        time.sleep(1.5)
        pc.control(cfg, "restart", f"liquibase-{name}")
        via = "process-compose"
    else:
        subprocess.Popen([sys.executable, "-m", "devctl", "_db", name], start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, cwd=str(Path(__file__).resolve().parent.parent),
                         env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent.parent)})
        via = "detached process"
    return {"environment": name, "action": action, "via": via}


def process_request(cfg: dict, name: str) -> int:
    """Body of the ``liquibase-<env>`` process: consume the pending request (no-op without one) and run it."""
    f = request_file(name)
    if not f.exists():
        print(f"liquibase-{name}: no pending request")
        return 0
    req = json.loads(f.read_text())
    f.unlink(missing_ok=True)
    return job(cfg, name, req["action"], req.get("arg", ""), req.get("build_id", ""))


def job(cfg: dict, name: str, action: str, arg: str = "", build_id: str = "") -> int:
    d = jobs_dir(cfg, name)
    d.mkdir(parents=True, exist_ok=True)
    log = d / f"{time.strftime('%Y%m%d-%H%M%S')}-{action}.log"
    meta = {"environment": name, "action": action, "arg": arg, "build_id": build_id, "status": "running",
            "started": time.time(), "finished": None, "rc": None, "log": log.name}
    (d / "last.json").write_text(json.dumps(meta))
    rc = 1
    with open(log, "w") as fh:
        try:
            with locked(cfg, name):
                print(f"# liquibase {action} {arg} -> {name}", file=fh, flush=True)
                tee = _Tee(fh)
                rc = run_script(cfg, name, action, arg, migrations_for(cfg, build_id), out=tee)
        except Exception as e:
            print(f"ERROR: {e}", file=fh, flush=True)
    meta.update(status="success" if rc == 0 else "failed", finished=time.time(), rc=rc)
    (d / "last.json").write_text(json.dumps(meta))
    return rc


class _Tee:
    def __init__(self, fh):
        self.fh = fh

    def write(self, s):
        self.fh.write(s)
        sys.stdout.write(s)

    def flush(self):
        self.fh.flush()
        sys.stdout.flush()


def last(cfg: dict, name: str) -> dict:
    _env(cfg, name)
    f = jobs_dir(cfg, name) / "last.json"
    if not f.exists():
        return {"status": "never run"}
    meta = json.loads(f.read_text())
    lg = jobs_dir(cfg, name) / meta["log"]
    meta["text"] = lg.read_text(errors="replace")[-60000:] if lg.exists() else ""
    return meta
