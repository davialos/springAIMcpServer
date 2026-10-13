"""Environment definitions (dashboard-managed): where a build can be deployed and which database it migrates."""
from __future__ import annotations

import re

from . import config, dbrun

NAME = re.compile(r"[a-z][a-z0-9-]{0,30}")
DB_KEYS = ("type", "host", "port", "name", "schema", "url", "username", "username_env", "password_env")
ENV_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def normalize(cfg: dict, name: str, body: dict) -> dict:
    if not NAME.fullmatch(name):
        raise ValueError("environment name: lowercase letters, digits, '-' (max 31, starts with a letter)")
    out = {"description": str(body.get("description", ""))[:200],
           "services": [s for s in body.get("services", [])],
           "allowed_branches": [str(b) for b in body.get("allowed_branches", []) if str(b).strip()],
           "health_timeout": int(body.get("health_timeout", 120)),
           "auto_rollback": bool(body.get("auto_rollback", True)),
           "contexts": str(body.get("contexts", ""))}
    for s in out["services"]:
        if s not in cfg["services"]:
            raise ValueError(f"unknown service: {s}")
    if not 5 <= out["health_timeout"] <= 3600:
        raise ValueError("health_timeout must be 5..3600 seconds")
    db = {k: str(v).strip() for k, v in (body.get("db") or {}).items() if k in DB_KEYS and str(v).strip()}
    if db:
        if db.get("type") and db["type"] not in dbrun.DB_TYPES:
            raise ValueError(f"db.type must be one of {', '.join(dbrun.DB_TYPES)}")
        if db.get("port") and not db["port"].isdigit():
            raise ValueError("db.port must be a number")
        if db.get("url") and not db["url"].startswith("jdbc:"):
            raise ValueError("db.url must be a JDBC url (jdbc:...)")
        for k in ("host", "name", "schema"):
            if db.get(k) and not re.fullmatch(r"[A-Za-z0-9_.:\-\[\]]+", db[k]):
                raise ValueError(f"db.{k} contains invalid characters")
        for k in ("username_env", "password_env"):
            if db.get(k) and not ENV_NAME.fullmatch(db[k]):
                raise ValueError(f"db.{k} must be an environment variable name (the secret itself is never stored here)")
        if out["contexts"] and not re.fullmatch(r"[A-Za-z0-9_,!&| ()-]+", out["contexts"]):
            raise ValueError("contexts contains invalid characters")
    out["db"] = db
    return out


def save(cfg: dict, name: str, body: dict) -> dict:
    e = normalize(cfg, name, body)
    cfg.setdefault("environments", {})[name] = e
    config.save(cfg)
    return e


def delete(cfg: dict, name: str) -> None:
    if name not in cfg.get("environments", {}):
        raise ValueError(f"unknown environment: {name}")
    from . import deployments
    if deployments.busy(cfg, name):
        raise ValueError(f"environment {name} has a running deployment")
    del cfg["environments"][name]
    config.save(cfg)
