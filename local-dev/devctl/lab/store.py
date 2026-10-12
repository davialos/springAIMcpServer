"""Folder/JSON persistence for API Lab projects (one owner per fact: each file is the source of truth for its kind)."""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

from .. import compose, config

NAME = re.compile(r"[A-Za-z0-9._-]{1,80}")


def root(cfg: dict) -> Path:
    return Path(str(cfg.get("apilab_dir") or "~/localdev/apilab")).expanduser()


def check(name: str, what: str = "name") -> str:
    if not NAME.fullmatch(name or ""):
        raise ValueError(f"bad {what} '{name}': letters, digits, . _ - only")
    return name


def pdir(cfg: dict, project: str) -> Path:
    d = root(cfg) / check(project, "project")
    if not (d / "project.json").exists():
        raise ValueError(f"unknown API Lab project: {project} (known: {', '.join(list_projects(cfg)) or 'none'})")
    return d


def read(p: Path, default=None):
    try:
        return json.loads(p.read_text())
    except (OSError, ValueError):
        return default


def write(p: Path, obj) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, indent=2, default=str) + "\n")
    tmp.replace(p)


def list_projects(cfg: dict) -> list:
    r = root(cfg)
    return sorted(d.name for d in r.iterdir() if (d / "project.json").exists()) if r.is_dir() else []


def create_project(cfg: dict, name: str, repo: str = "", service: str = "", base_url: str = "") -> dict:
    d = root(cfg) / check(name, "project")
    if repo:
        from .. import repos
        repo_path = str(repos.repo_path(cfg, repo)) if "/" not in repo else str(Path(repo).expanduser())
    else:
        repo_path = ""
    if service and service not in cfg["services"]:
        raise ValueError(f"unknown service: {service}")
    if not base_url and service:
        base_url = compose.service_base_url(service, cfg["services"][service], cfg) or ""
    proj = read(d / "project.json", {}) or {"name": name, "created": time.time()}
    proj.update({k: v for k, v in {"repo": repo_path, "service": service, "base_url": base_url.rstrip("/")}.items() if v})
    write(d / "project.json", proj)
    for sub in ("apis", "rules", "data", "workflows", "runs"):
        (d / sub).mkdir(parents=True, exist_ok=True)
    return proj


def project(cfg: dict, name: str) -> dict:
    return read(pdir(cfg, name) / "project.json", {})


def update_project(cfg: dict, name: str, **kw) -> dict:
    p = pdir(cfg, name) / "project.json"
    proj = read(p, {})
    proj.update({k: v for k, v in kw.items() if v is not None})
    write(p, proj)
    return proj


def apis(cfg: dict, name: str) -> list:
    return sorted((read(f, {}) for f in (pdir(cfg, name) / "apis").glob("*.json")), key=lambda a: (a.get("path", ""), a.get("method", "")))


def api(cfg: dict, name: str, api_id: str) -> dict:
    a = read(pdir(cfg, name) / "apis" / f"{check(api_id, 'api')}.json")
    if not a:
        raise ValueError(f"unknown API {api_id} in {name}")
    return a


def save_apis(cfg: dict, name: str, items: list, replace: bool = False) -> int:
    d = pdir(cfg, name) / "apis"
    if replace:
        for f in d.glob("*.json"):
            f.unlink()
    for a in items:
        write(d / f"{check(a['id'], 'api')}.json", a)
    return len(items)


def links(cfg: dict, name: str) -> list:
    return read(pdir(cfg, name) / "links.json", []) or []


def save_links(cfg: dict, name: str, items: list) -> None:
    write(pdir(cfg, name) / "links.json", sorted(items, key=lambda l: (l["to"]["api"], l["to"]["field"], -l.get("confidence", 0))))


def rules(cfg: dict, name: str, api_id: str) -> dict:
    return read(pdir(cfg, name) / "rules" / f"{check(api_id, 'api')}.json", {}) or {}


def save_rules(cfg: dict, name: str, api_id: str, r: dict) -> None:
    write(pdir(cfg, name) / "rules" / f"{check(api_id, 'api')}.json", r)


def data(cfg: dict, name: str, api_id: str) -> list:
    return read(pdir(cfg, name) / "data" / f"{check(api_id, 'api')}.json", []) or []


def save_data(cfg: dict, name: str, api_id: str, rows: list) -> None:
    write(pdir(cfg, name) / "data" / f"{check(api_id, 'api')}.json", rows)


def workflows(cfg: dict, name: str) -> list:
    return sorted((read(f, {}) for f in (pdir(cfg, name) / "workflows").glob("*.json")), key=lambda w: w.get("id", ""))


def workflow(cfg: dict, name: str, wf: str) -> dict:
    w = read(pdir(cfg, name) / "workflows" / f"{check(wf, 'workflow')}.json")
    if not w:
        raise ValueError(f"unknown workflow {wf} in {name}")
    return w


def save_workflow(cfg: dict, name: str, wf: dict) -> None:
    write(pdir(cfg, name) / "workflows" / f"{check(wf['id'], 'workflow')}.json", wf)


def runs(cfg: dict, name: str, limit: int = 30) -> list:
    files = sorted((pdir(cfg, name) / "runs").glob("*.json"), reverse=True)[:limit]
    out = []
    for f in files:
        r = read(f, {})
        out.append({k: r.get(k) for k in ("id", "workflow", "started", "iterations", "ok", "failed", "summary")})
    return out


def run(cfg: dict, name: str, run_id: str) -> dict:
    r = read(pdir(cfg, name) / "runs" / f"{check(run_id, 'run')}.json")
    if not r:
        raise ValueError(f"unknown run {run_id}")
    return r


def pool(cfg: dict, name: str) -> dict:
    return read(pdir(cfg, name) / "pool.json", {}) or {}


def save_pool(cfg: dict, name: str, p: dict) -> None:
    write(pdir(cfg, name) / "pool.json", p)
