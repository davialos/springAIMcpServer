"""Workflows: ordered API steps whose request attributes are bound to earlier responses (the threads), executed
against the local service, recorded, and exported as k6 load tests.

Workflow JSON (workflows/<id>.json):
  {"id": "order-flow", "name": "...", "think_time_ms": 300,
   "steps": [{"id": "s1", "api": "createCustomer", "bind": {}, "expect": {"status": [200, 201]}},
             {"id": "s2", "api": "createOrder", "bind": {"body.customerId": "{{s1.response.id}}"}}]}
Binding values: "{{<step>.response.<path>}}", "{{<step>.request.<path>}}", "{{pool:<api>.<field>}}" or a literal; strings
may embed expressions ("ORD-{{s1.response.id}}"). Response paths are dotted, "items[].id" takes the first item.
"""
from __future__ import annotations

import json
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from . import fake, links as linkmod, store

EXPR = re.compile(r"\{\{\s*([^}]+?)\s*\}\}")
POOL_MAX = 200


def get_path(obj, path: str):
    if path in ("", "$"):
        return obj
    cur = obj
    for seg in re.split(r"\.(?![^\[]*\])", path):
        if seg == "[]":
            cur = cur[0] if isinstance(cur, list) and cur else None
            continue
        arr = seg.endswith("[]")
        key = seg[:-2] if arr else seg
        m = re.fullmatch(r"(\w*)\[(\d+)\]", seg)
        if m:
            key, idx = m.group(1), int(m.group(2))
            cur = cur.get(key) if key and isinstance(cur, dict) else cur
            cur = cur[idx] if isinstance(cur, list) and len(cur) > idx else None
            continue
        if key:
            cur = cur.get(key) if isinstance(cur, dict) else None
        if arr:
            cur = cur[0] if isinstance(cur, list) and cur else None
        if cur is None:
            return None
    return cur


def set_req(req: dict, field: str, value) -> None:
    loc, _, rest = field.partition(".")
    if loc in ("path", "query", "header"):
        req[loc][rest] = value
        return
    if loc.startswith("body"):
        if req.get("body") is None:
            req["body"] = [] if loc == "body[]" else {}
        target = req["body"]
        if isinstance(target, list):
            target = target[0] if target else target.append({}) or target[0]
        parts = re.split(r"\.(?![^\[]*\])", rest) if rest else []
        for i, p in enumerate(parts):
            last = i == len(parts) - 1
            key = p[:-2] if p.endswith("[]") else p
            if last:
                target[key] = value
            else:
                nxt = target.setdefault(key, [] if p.endswith("[]") else {})
                if isinstance(nxt, list):
                    if not nxt:
                        nxt.append({})
                    nxt = nxt[0]
                target = nxt


# ---------------------------------------------------------------- building workflows

def auto(cfg: dict, project: str, name: str = "", include_unlinked: bool = False, min_confidence: float = 0.0) -> dict:
    """Order APIs so producers run before consumers (topological over accepted links) and bind every accepted thread."""
    apis = {a["id"]: a for a in store.apis(cfg, project)}
    ls = [l for l in store.links(cfg, project) if l.get("status") == "accepted" and l.get("confidence", 1) >= min_confidence
          and l["from"]["api"] in apis and l["to"]["api"] in apis]
    if not ls and not include_unlinked:
        raise ValueError("no accepted links yet - accept threads first (apilab_link_set / Threads view), or include_unlinked")
    nodes = set()
    for l in ls:
        nodes |= {l["from"]["api"], l["to"]["api"]}
    if include_unlinked:
        nodes |= set(apis)
    deps = {n: {l["from"]["api"] for l in ls if l["to"]["api"] == n and l["from"]["api"] != n} for n in nodes}
    rank = {"POST": 0, "PUT": 1, "PATCH": 2, "GET": 3, "DELETE": 9}
    order, done = [], set()
    while len(order) < len(nodes):
        ready = sorted((n for n in nodes if n not in done and deps[n] <= done), key=lambda n: (rank.get(apis[n]["method"], 5), apis[n]["path"]))
        if not ready:  # cycle: break it at the most "creating" API
            ready = sorted((n for n in nodes if n not in done), key=lambda n: rank.get(apis[n]["method"], 5))[:1]
        for n in ready:
            order.append(n)
            done.add(n)
    deletes = [n for n in order if apis[n]["method"] == "DELETE"]
    order = [n for n in order if n not in deletes] + deletes  # destroy last
    steps, sid_of = [], {}
    for i, n in enumerate(order, 1):
        sid = f"s{i}"
        bind = {}
        for l in ls:
            if l["to"]["api"] == n and l["from"]["api"] in sid_of:
                bind[l["to"]["field"]] = "{{" + f"{sid_of[l['from']['api']]}.response.{l['from']['field']}" + "}}"
        sid_of[n] = sid
        steps.append({"id": sid, "api": n, "bind": bind, "expect": {"status": [200, 201, 202, 204]}})
    wid = store.check(name or f"auto-{time.strftime('%Y%m%d-%H%M%S')}", "workflow")
    wf = {"id": wid, "name": name or "Auto workflow from accepted threads", "think_time_ms": 300, "steps": steps,
          "created_by": "auto", "created": time.time()}
    store.save_workflow(cfg, project, wf)
    return wf


def validate(cfg: dict, project: str, wf: dict) -> dict:
    apis = {a["id"]: a for a in store.apis(cfg, project)}
    if not isinstance(wf.get("steps"), list) or not wf["steps"]:
        raise ValueError("workflow needs steps: [{id, api, bind?, expect?}]")
    wf["id"] = store.check(wf.get("id") or f"wf-{int(time.time())}", "workflow")
    seen = []
    for i, st in enumerate(wf["steps"], 1):
        st.setdefault("id", f"s{i}")
        if st.get("api") not in apis:
            raise ValueError(f"step {st['id']}: unknown API {st.get('api')}")
        req_fields = {f["path"] for f in apis[st["api"]].get("request", [])}
        for field, val in (st.get("bind") or {}).items():
            if field not in req_fields and not field.startswith(("header.", "query.")):
                raise ValueError(f"step {st['id']}: {st['api']} has no request field '{field}'")
            for ref in EXPR.findall(str(val)):
                if not ref.startswith("pool:") and ref.split(".")[0] not in seen:
                    raise ValueError(f"step {st['id']}: '{ref}' refers to a step that does not run before it")
        seen.append(st["id"])
    wf.setdefault("think_time_ms", 300)
    wf.setdefault("created", time.time())
    return wf


# ---------------------------------------------------------------- running workflows

def _resolve(val, ctx: dict, pool: dict, rng):
    if not isinstance(val, str):
        return val
    whole = EXPR.fullmatch(val.strip())

    def look(ref):
        if ref.startswith("pool:"):
            vs = pool.get(ref[5:]) or []
            return rng.choice(vs) if vs else None
        sid, _, rest = ref.partition(".")
        side, _, path = rest.partition(".")
        st = ctx.get(sid)
        return get_path(st.get(side), path) if st else None
    if whole:
        return look(whole.group(1))
    return EXPR.sub(lambda m: str(look(m.group(1))), val)


def _fill_links(req: dict, ctx_by_api: dict, pool: dict, rng, warnings: list):
    def walk(o, path):
        if isinstance(o, dict):
            if set(o) == {"$link"}:
                api_id, _, field = o["$link"].partition(".")
                if api_id in ctx_by_api:
                    return get_path(ctx_by_api[api_id], field)
                vs = pool.get(o["$link"]) or []
                if vs:
                    return rng.choice(vs)
                warnings.append(f"{path}: no value yet for {o['$link']} (add a step for {api_id} or run it once)")
                return None
            return {k: walk(v, f"{path}.{k}") for k, v in o.items()}
        if isinstance(o, list):
            return [walk(v, f"{path}[]") for v in o]
        return o
    for loc in ("path", "query", "header"):
        req[loc] = walk(req[loc], loc)
    req["body"] = walk(req["body"], "body")


def _http(proj: dict, api: dict, req: dict, timeout: float) -> dict:
    path = api["path"]
    for k, v in req["path"].items():
        path = path.replace("{" + k + "}", urllib.parse.quote(str(v if v is not None else ""), safe=""))
    q = {k: v for k, v in req["query"].items() if v is not None}
    url = proj["base_url"].rstrip("/") + path + (("?" + urllib.parse.urlencode(q, doseq=True)) if q else "")
    headers = {"Accept": "application/json", "User-Agent": "devctl-apilab"}
    headers.update({k: str(v) for k, v in req["header"].items() if v is not None})
    headers.update(proj.get("headers") or {})  # project headers (auth) always win over generated values
    data = None
    if req.get("body") is not None and api["method"] in ("POST", "PUT", "PATCH", "DELETE"):
        data = json.dumps(req["body"]).encode()
        headers["Content-Type"] = "application/json"
    t0 = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=api["method"], headers=headers), timeout=timeout) as r:
            status, raw = r.status, r.read(2_000_000)
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read(2_000_000)
    except Exception as e:
        return {"url": url, "status": 0, "error": f"{type(e).__name__}: {e}", "ms": int((time.time() - t0) * 1000)}
    text = raw.decode("utf-8", "replace")
    try:
        body = json.loads(text) if text.strip() else None
    except ValueError:
        body = text[:4000]
    return {"url": url, "status": status, "ms": int((time.time() - t0) * 1000), "response": body}


def _capture(pool: dict, api: dict, response, link_sources: set) -> None:
    for f in api.get("response", []):
        key = f"{api['id']}.{f['path']}"
        if key not in link_sources and not re.search(r"(?i)(^|\.|\[\])(id|uuid|code|key)$|Id$", f["path"]):
            continue
        v = get_path(response, f["path"])
        if isinstance(v, (str, int, float)) and v != "":
            vals = pool.setdefault(key, [])
            if v not in vals:
                vals.append(v)
                del vals[:-POOL_MAX]


def run(cfg: dict, project: str, wf_id: str, iterations: int = 1, timeout: float = 15.0, stop_on_error: bool = True) -> dict:
    proj = store.project(cfg, project)
    if not proj.get("base_url"):
        raise ValueError("project has no base_url - set it (apilab_create_project base_url=... or service=...)")
    wf = store.workflow(cfg, project, wf_id)
    apis = {a["id"]: a for a in store.apis(cfg, project)}
    all_links = store.links(cfg, project)
    link_sources = {f"{l['from']['api']}.{l['from']['field']}" for l in all_links if l.get("status") != "rejected"}
    pool = store.pool(cfg, project)
    run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{wf['id']}"[:80]
    out = {"id": run_id, "workflow": wf["id"], "project": project, "base_url": proj["base_url"], "started": time.time(), "iterations": []}
    ok = failed = 0
    for it in range(max(1, min(int(iterations), 500))):
        rng = random.Random(int(time.time() * 1000) + it)
        ctx, by_api, steps = {}, {}, []
        for st in wf["steps"]:
            api = apis[st["api"]]
            rules = store.rules(cfg, project, api["id"])
            req = fake.record(api, rules, linkmod.accepted_for(all_links, api["id"]), {}, rng, it + 1)
            warnings: list = []
            bound = {}
            for field, expr in (st.get("bind") or {}).items():
                v = _resolve(expr, ctx, pool, rng)
                set_req(req, field, v)
                bound[field] = {"from": expr, "value": v}
            _fill_links(req, by_api, pool, rng, warnings)
            res = _http(proj, api, req, timeout)
            expect = (st.get("expect") or {}).get("status") or [200, 201, 202, 204]
            good = res.get("status") in expect
            ok, failed = ok + good, failed + (not good)
            ctx[st["id"]] = {"request": req, "response": res.get("response")}
            by_api[api["id"]] = res.get("response")
            if good:
                _capture(pool, api, res.get("response"), link_sources)
            steps.append({"step": st["id"], "api": api["id"], "method": api["method"], "request": req, "bound": bound,
                          "warnings": warnings, "ok": good, **res})
            if not good and stop_on_error:
                break
            if wf.get("think_time_ms"):
                time.sleep(min(2.0, wf["think_time_ms"] / 1000) if iterations > 1 else 0)
        out["iterations"].append(steps)
    out.update(ok=ok, failed=failed, finished=time.time())
    per = {}
    for steps in out["iterations"]:
        for s in steps:
            p = per.setdefault(s["step"], {"api": s["api"], "calls": 0, "ok": 0, "ms": 0})
            p["calls"] += 1
            p["ok"] += s["ok"]
            p["ms"] += s.get("ms", 0)
    out["summary"] = {k: {**v, "avg_ms": round(v["ms"] / max(1, v["calls"]), 1)} for k, v in per.items()}
    store.write(store.pdir(cfg, project) / "runs" / f"{run_id}.json", out)
    store.save_pool(cfg, project, pool)
    return out


# ---------------------------------------------------------------- k6 export

def export_k6(cfg: dict, project: str, wf_id: str, name: str = "", profile: str = "load", vus: int = 10, duration: str = "1m",
              rows: int = 200, p95_ms: int = 800, max_error_rate: float = 0.02) -> dict:
    from .. import config as dconf, loadgen
    proj = store.project(cfg, project)
    wf = store.workflow(cfg, project, wf_id)
    apis = {a["id"]: a for a in store.apis(cfg, project)}
    all_links = store.links(cfg, project)
    pool = store.pool(cfg, project)
    if profile not in loadgen.PROFILES:
        raise ValueError(f"profile: {', '.join(loadgen.PROFILES)}")
    name = store.check(name or f"{project}-{wf['id']}", "load test name")
    rng = random.Random(7)
    data = []
    for i in range(max(1, min(int(rows), 5000))):
        data.append({st["id"]: fake.record(apis[st["api"]], store.rules(cfg, project, st["api"]),
                                           linkmod.accepted_for(all_links, st["api"]), pool, rng, i + 1) for st in wf["steps"]})
    steps = [{"id": st["id"], "api": st["api"], "method": apis[st["api"]]["method"], "path": apis[st["api"]]["path"],
              "bind": st.get("bind") or {}, "expect": (st.get("expect") or {}).get("status") or [200, 201, 202, 204]} for st in wf["steps"]]
    opts = {"stages": loadgen.PROFILES[profile](max(1, int(vus)), duration),
            "thresholds": {"http_req_failed": [f"rate<{max_error_rate}"], "http_req_duration": [f"p(95)<{int(p95_ms)}"],
                           "checks": ["rate>0.95"]}}
    d = dconf.path(cfg, "loadtest_dir")
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{name}.data.json").write_text(json.dumps(data))
    (d / f"{name}.js").write_text(K6_TEMPLATE.replace("__NAME__", name).replace("__BASE__", json.dumps(proj.get("base_url", "")))
                                  .replace("__STEPS__", json.dumps(steps, indent=2)).replace("__OPTIONS__", json.dumps(opts, indent=2))
                                  .replace("__HEADERS__", json.dumps(proj.get("headers") or {}))
                                  .replace("__THINK__", str(float(wf.get("think_time_ms", 300)) / 1000)))
    cfg.setdefault("loadtests", {})[name] = {"script": f"{name}.js", "service": proj.get("service") or None, "profile": profile,
                                              "vus": int(vus), "duration": duration, "jfr": True, "env": {}, "apilab": {"project": project, "workflow": wf["id"]}}
    dconf.save(cfg)
    return {"loadtest": name, "script": str(d / f"{name}.js"), "data": str(d / f"{name}.data.json"), "rows": len(data),
            "next": f"loadtest_run name={name}"}


K6_TEMPLATE = r"""// Generated by devctl API Lab from workflow __NAME__ - threads between steps are resolved per iteration.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || __BASE__;
const STEPS = __STEPS__;
const HEADERS = Object.assign({ 'Content-Type': 'application/json', Accept: 'application/json' }, __HEADERS__,
  __ENV.AUTH_TOKEN ? { Authorization: `Bearer ${__ENV.AUTH_TOKEN}` } : {});
const DATA = new SharedArray('rows', () => JSON.parse(open('./__NAME__.data.json')));

export const options = __OPTIONS__;

function get(obj, path) {
  if (!path) return obj;
  let cur = obj;
  for (const seg of path.split('.')) {
    if (cur === null || cur === undefined) return undefined;
    if (seg === '[]') { cur = Array.isArray(cur) ? cur[0] : undefined; continue; }
    const arr = seg.endsWith('[]'); const key = arr ? seg.slice(0, -2) : seg;
    if (key) cur = cur[key];
    if (arr) cur = Array.isArray(cur) ? cur[0] : undefined;
  }
  return cur;
}
function resolve(v, ctx, byApi) {
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    if (Object.keys(v).length === 1 && v.$link) { const [api, ...f] = v.$link.split('.'); return get(byApi[api], f.join('.')); }
    const o = {}; for (const k of Object.keys(v)) o[k] = resolve(v[k], ctx, byApi); return o;
  }
  if (Array.isArray(v)) return v.map((x) => resolve(x, ctx, byApi));
  if (typeof v !== 'string') return v;
  const look = (ref) => { const [sid, side, ...p] = ref.trim().split('.'); return ctx[sid] ? get(ctx[sid][side], p.join('.')) : undefined; };
  const whole = v.match(/^\{\{([^}]+)\}\}$/);
  if (whole) return look(whole[1]);
  return v.replace(/\{\{([^}]+)\}\}/g, (_, r) => String(look(r)));
}
function setField(req, field, value) {
  const [loc, ...rest] = field.split('.');
  if (loc === 'path' || loc === 'query' || loc === 'header') { req[loc][rest.join('.')] = value; return; }
  if (req.body === null || req.body === undefined) req.body = loc === 'body[]' ? [{}] : {};
  let t = Array.isArray(req.body) ? req.body[0] : req.body;
  rest.forEach((p, i) => {
    const key = p.endsWith('[]') ? p.slice(0, -2) : p;
    if (i === rest.length - 1) { t[key] = value; return; }
    if (t[key] === undefined) t[key] = p.endsWith('[]') ? [{}] : {};
    t = Array.isArray(t[key]) ? t[key][0] : t[key];
  });
}

export default function () {
  const row = DATA[(__VU * 7919 + __ITER) % DATA.length];
  const ctx = {}; const byApi = {};
  for (const st of STEPS) {
    const req = JSON.parse(JSON.stringify(row[st.id]));
    for (const loc of ['path', 'query', 'header']) req[loc] = resolve(req[loc], ctx, byApi);
    req.body = resolve(req.body, ctx, byApi);
    for (const field of Object.keys(st.bind)) setField(req, field, resolve(st.bind[field], ctx, byApi));
    let path = st.path;
    for (const k of Object.keys(req.path)) path = path.replace(`{${k}}`, encodeURIComponent(String(req.path[k])));
    const qs = Object.keys(req.query).filter((k) => req.query[k] !== null && req.query[k] !== undefined)
      .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(req.query[k]))}`).join('&');
    const body = req.body === null || st.method === 'GET' ? null : JSON.stringify(req.body);
    const res = http.request(st.method, BASE_URL + path + (qs ? `?${qs}` : ''), body,
      { headers: Object.assign({}, HEADERS, req.header), tags: { name: `${st.method} ${st.path}` } });
    const ok = check(res, { [`${st.api} status ok`]: (r) => st.expect.includes(r.status) });
    let json = null; try { json = res.json(); } catch (e) { json = null; }
    ctx[st.id] = { request: req, response: json }; byApi[st.api] = json;
    if (!ok) break;
  }
  sleep(__THINK__ * (0.5 + Math.random()));
}
"""
