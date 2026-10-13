"""API Lab operations used by the MCP tools, the dashboard and the CLI (one place, same behaviour everywhere)."""
from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path

from .. import javahome
from . import discover as disc, fake, flow, links as linkmod, store


def projects(cfg: dict) -> list:
    out = []
    for n in store.list_projects(cfg):
        p = store.project(cfg, n)
        ls = store.links(cfg, n)
        out.append({**p, "apis": len(store.apis(cfg, n)), "links": len(ls),
                    "accepted": sum(1 for l in ls if l.get("status") == "accepted"), "workflows": len(store.workflows(cfg, n))})
    return out


def create(cfg: dict, name: str, repo: str = "", service: str = "", base_url: str = "", headers: dict | None = None) -> dict:
    p = store.create_project(cfg, name, repo, service, base_url)
    if headers:
        p = store.update_project(cfg, name, headers=headers)
    return p


def run_discovery(cfg: dict, project: str, source: str = "auto", replace: bool = True) -> dict:
    proj = store.project(cfg, project)
    found, used = disc.discover(proj, source)
    store.save_apis(cfg, project, found, replace=replace)
    store.update_project(cfg, project, discovered=time.time(), discovery_source=used)
    current = store.apis(cfg, project)
    ids = {a["id"] for a in current}
    sug = linkmod.suggest(current)
    # threads that point at APIs which no longer exist (renamed / removed / other discovery source) are dropped
    kept = [l for l in store.links(cfg, project) if l["from"]["api"] in ids and l["to"]["api"] in ids]
    merged = linkmod.merge(kept, sug)
    store.save_links(cfg, project, merged)
    return {"project": project, "source": used, "apis": len(found), "suggested_links": len(sug),
            "next": "review threads (apilab_links) - accept the right ones with apilab_link_set, then apilab_set_rules / apilab_generate"}


def api_list(cfg: dict, project: str) -> list:
    ls = store.links(cfg, project)
    out = []
    for a in store.apis(cfg, project):
        out.append({"id": a["id"], "method": a["method"], "path": a["path"], "summary": a.get("summary", ""),
                    "request_fields": len(a.get("request", [])), "response_fields": len(a.get("response", [])),
                    "rules": len(store.rules(cfg, project, a["id"])), "rows": len(store.data(cfg, project, a["id"])),
                    "links_in": sum(1 for l in ls if l["to"]["api"] == a["id"] and l.get("status") == "accepted")})
    return out


def api_detail(cfg: dict, project: str, api_id: str) -> dict:
    a = store.api(cfg, project, api_id)
    ls = store.links(cfg, project)
    return {**a, "rules": store.rules(cfg, project, api_id),
            "links_in": [l for l in ls if l["to"]["api"] == api_id], "links_out": [l for l in ls if l["from"]["api"] == api_id],
            "sample": (store.data(cfg, project, api_id) or [None])[0]}


def links_view(cfg: dict, project: str, status: str = "", api: str = "", min_confidence: float = 0.0) -> list:
    return [l for l in store.links(cfg, project) if (not status or l.get("status") == status)
            and (not api or api in (l["from"]["api"], l["to"]["api"])) and l.get("confidence", 1) >= min_confidence]


def link_set(cfg: dict, project: str, from_api: str, from_field: str, to_api: str, to_field: str, status: str = "accepted",
             reason: str = "", source: str = "ai") -> dict:
    ls = store.links(cfg, project)
    apis = {a["id"]: a for a in store.apis(cfg, project)}
    link = linkmod.upsert(ls, apis, from_api, from_field, to_api, to_field, status, reason, source)
    if status == "accepted":  # one accepted producer per consumer field: demote the alternatives
        for l in ls:
            if l["id"] != link["id"] and l["to"] == link["to"] and l.get("status") == "accepted":
                l["status"] = "suggested"
    store.save_links(cfg, project, ls)
    return link


def link_status(cfg: dict, project: str, link_ids: list, status: str) -> dict:
    if status not in ("accepted", "suggested", "rejected", "delete"):
        raise ValueError("status: accepted, suggested, rejected or delete")
    ls = store.links(cfg, project)
    ids = set(link_ids)
    changed = 0
    if status == "delete":
        before = len(ls)
        ls = [l for l in ls if l["id"] not in ids]
        changed = before - len(ls)
    else:
        for l in ls:
            if l["id"] in ids:
                l["status"], changed = status, changed + 1
        if status == "accepted":
            acc = {}
            for l in sorted(ls, key=lambda l: l["id"] not in ids):
                key = (l["to"]["api"], l["to"]["field"])
                if l.get("status") == "accepted":
                    if key in acc:
                        l["status"] = "suggested"
                    acc[key] = l["id"]
    store.save_links(cfg, project, ls)
    return {"changed": changed}


def set_rules(cfg: dict, project: str, api_id: str, rules: dict, replace: bool = False) -> dict:
    a = store.api(cfg, project, api_id)
    fields = {f["path"]: f for f in a.get("request", [])}
    known = set(fields)
    bad = [k for k in rules if k not in known]
    if bad:
        raise ValueError(f"{api_id} has no request fields {bad}; known: {sorted(known)}")
    for k, r in rules.items():
        if not isinstance(r, dict):
            raise ValueError(f"rule for {k} must be an object like {{\"kind\": \"email\"}}")
        if r.get("kind") and r["kind"] not in fake.KINDS:
            raise ValueError(f"unknown kind {r['kind']}; one of {sorted(fake.KINDS)}")
        allowed = fields.get(k, {}).get("enum")
        if allowed and r.get("enum") and not set(r["enum"]) <= set(allowed):  # the code's enum is authoritative
            raise ValueError(f"{k}: {sorted(set(r['enum']) - set(allowed))} not in the API's enum {allowed} - use a subset")
    # sample each new rule against the field's own contract - a rule that sometimes produces invalid values
    # (e.g. a date pattern giving month 00) is rejected with an example instead of failing 1 request in 10 later
    import random as _r
    rng = _r.Random(42)
    for k, r in rules.items():
        f = fields[k]
        if r.get("omit"):
            continue
        for i in range(50):
            v = fake._value(f, r, None, {}, rng, i + 1)
            why = fake.violation(f, v)
            if why:
                hint = (" - for dates use {\"kind\": \"date\"|\"past_date\"|\"future_date\", \"min\": \"-2y\", \"max\": \"0d\"}"
                        if f.get("format") in ("date", "date-time") else "")
                raise ValueError(f"rule for {k} generates invalid values ({why}){hint}")
    cur = {} if replace else store.rules(cfg, project, api_id)
    cur.update(rules)
    store.save_rules(cfg, project, api_id, cur)
    return {"api": api_id, "rules": cur, "preview": generate(cfg, project, api_id, 1, save=False)["rows"][0]}


def generate(cfg: dict, project: str, api_id: str = "", count: int = 20, save: bool = True, seed: int | None = None) -> dict:
    ids = [api_id] if api_id else [a["id"] for a in store.apis(cfg, project) if a.get("request")]
    ls, pool, res = store.links(cfg, project), store.pool(cfg, project), {}
    for i in ids:
        a = store.api(cfg, project, i)
        rows = fake.generate(a, store.rules(cfg, project, i), linkmod.accepted_for(ls, i), pool, count, seed)
        if save:
            store.save_data(cfg, project, i, rows)
        res[i] = rows
    if api_id:
        return {"api": api_id, "rows": res[api_id], "saved": save}
    return {"generated": {k: len(v) for k, v in res.items()}, "saved": save}


def data_view(cfg: dict, project: str, api_id: str, limit: int = 50) -> dict:
    rows = store.data(cfg, project, api_id)
    return {"api": api_id, "total": len(rows), "rows": rows[: max(1, int(limit))],
            "file": str(store.pdir(cfg, project) / "data" / f"{api_id}.json")}


def workflow_save(cfg: dict, project: str, wf: dict) -> dict:
    wf = flow.validate(cfg, project, dict(wf))
    wf.setdefault("created_by", "ai")
    store.save_workflow(cfg, project, wf)
    return wf


def java_suite(cfg: dict, project: str, timeout: int = 900) -> dict:
    """The repository's full Java generator (DB-backed real values, journeys): scripts/loadtest.sh generate."""
    proj = store.project(cfg, project)
    script = Path(__file__).resolve().parents[3] / "scripts" / "loadtest.sh"
    if not script.exists():
        raise ValueError("scripts/loadtest.sh not found (only available inside the springAIMcpServer repository)")
    if not proj.get("repo"):
        raise ValueError("project has no repo")
    out_dir = store.pdir(cfg, project) / "java-suite"
    cmd = [str(script), "generate", "--project", proj["repo"], "--out", str(out_dir)]
    if proj.get("base_url"):
        try:  # only hand the OpenAPI URL over when the service actually serves it
            disc._get_json(proj["base_url"] + "/v3/api-docs")
            cmd += ["--openapi", proj["base_url"] + "/v3/api-docs"]
        except Exception:
            pass
    env = dict(os.environ)
    jh = javahome.resolve((cfg.get("jfr") or {}).get("analyzer_java_home", "auto:25"))
    if jh:
        env["JAVA_HOME"] = jh
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)
    return {"ok": r.returncode == 0, "suite": str(out_dir), "output": (r.stdout + r.stderr)[-4000:],
            "run": f"cd {out_dir} && ./run.sh smoke   (or scripts/perf-test.sh --suite {out_dir} ...)"}
