"""Load-test setup: discover a running service's endpoints and generate a k6 script.

Discovery: <base>/v3/api-docs (springdoc) or <base>/actuator/mappings. For data-driven suites (real ids from the
database, HAR journeys) use the repository's full generator instead (scripts/loadtest.sh, scripts/perf-test.sh) and
register the produced script as a load test.
"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

from . import compose, config

PROFILES = {
    # stages relative to the target VUs (v) and hold duration (d)
    "smoke": lambda v, d: [{"duration": d, "target": 1}],
    "load": lambda v, d: [{"duration": "30s", "target": v}, {"duration": d, "target": v}, {"duration": "20s", "target": 0}],
    "stress": lambda v, d: [{"duration": "30s", "target": v}, {"duration": d, "target": v}, {"duration": "30s", "target": v * 2},
                            {"duration": d, "target": v * 2}, {"duration": "30s", "target": v * 3}, {"duration": d, "target": v * 3},
                            {"duration": "30s", "target": 0}],
    "spike": lambda v, d: [{"duration": "20s", "target": max(1, v // 5)}, {"duration": "10s", "target": v * 5},
                           {"duration": d, "target": v * 5}, {"duration": "10s", "target": max(1, v // 5)},
                           {"duration": "30s", "target": max(1, v // 5)}, {"duration": "10s", "target": 0}],
    "soak": lambda v, d: [{"duration": "1m", "target": v}, {"duration": d, "target": v}, {"duration": "30s", "target": 0}],
}
SKIP = re.compile(r"^/(actuator|error|v3/api-docs|swagger-ui|webjars)(/|$)")


def _get_json(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=5) as r:
        return json.loads(r.read(20_000_000))


def discover(cfg: dict, service: str = "", base_url: str = "") -> dict:
    if service:
        if service not in cfg["services"]:
            raise ValueError(f"unknown service: {service}")
        base_url = compose.service_base_url(service, cfg["services"][service], cfg) or ""
    if not base_url:
        raise ValueError("no base URL: give base_url, or a service with a port/base_url")
    base_url = base_url.rstrip("/")
    try:
        return {"base_url": base_url, "source": "openapi", "endpoints": from_openapi(_get_json(base_url + "/v3/api-docs"))}
    except Exception:
        pass
    try:
        return {"base_url": base_url, "source": "actuator-mappings", "endpoints": from_mappings(_get_json(base_url + "/actuator/mappings"))}
    except Exception as e:
        raise ValueError(f"could not discover endpoints at {base_url} (/v3/api-docs or /actuator/mappings): {e}")


def from_openapi(doc: dict) -> list:
    out = []
    for path, ops in doc.get("paths", {}).items():
        for method, op in ops.items():
            if method.upper() not in ("GET", "POST", "PUT", "PATCH", "DELETE") or SKIP.match(path):
                continue
            params = [p["name"] for p in op.get("parameters", []) if p.get("in") == "path"]
            out.append({"method": method.upper(), "path": path, "params": params, "summary": op.get("summary") or op.get("operationId", "")})
    return sorted(out, key=lambda e: (e["path"], e["method"]))


def from_mappings(doc: dict) -> list:
    out, seen = [], set()
    for ctx in doc.get("contexts", {}).values():
        for servlet in ctx.get("mappings", {}).get("dispatcherServlets", {}).values():
            for m in servlet:
                cond = (m.get("details") or {}).get("requestMappingConditions") or {}
                for path in cond.get("patterns", []):
                    if SKIP.match(path):
                        continue
                    for method in cond.get("methods") or ["GET"]:
                        if (method, path) in seen:
                            continue
                        seen.add((method, path))
                        out.append({"method": method, "path": path, "params": re.findall(r"\{([^}:]+)", path),
                                    "summary": m.get("handler", "")[:120]})
    return sorted(out, key=lambda e: (e["path"], e["method"]))


def generate(base_url: str, endpoints: list, profile: str = "load", vus: int = 10, duration: str = "1m",
             path_values: dict | None = None, headers: dict | None = None, p95_ms: int = 500, max_error_rate: float = 0.01,
             think_time: float = 0.5) -> str:
    if profile not in PROFILES:
        raise ValueError(f"profile must be one of {', '.join(PROFILES)}")
    if not endpoints:
        raise ValueError("select at least one endpoint")
    vus = max(1, int(vus))
    if not re.fullmatch(r"\d+(ms|s|m|h)", duration):
        raise ValueError("duration like 30s, 5m, 1h")
    eps = [{"method": e["method"].upper(), "path": e["path"], "weight": max(1, int(e.get("weight", 1))),
            "body": e.get("body")} for e in endpoints]
    opts = {"stages": PROFILES[profile](vus, duration),
            "thresholds": {"http_req_failed": [f"rate<{max_error_rate}"], "http_req_duration": [f"p(95)<{int(p95_ms)}"]},
            "summaryTrendStats": ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"]}
    return f"""// Generated by devctl (local-dev/devctl/loadgen.py) - profile {profile}. Safe to edit; regenerate overwrites.
import http from 'k6/http';
import {{ check, sleep }} from 'k6';

const BASE_URL = __ENV.BASE_URL || {json.dumps(base_url.rstrip('/'))};
const ENDPOINTS = {json.dumps(eps, indent=2)};
// path parameter values; override with -e PATH_VALUES='{{"id":["1","2"]}}'
const PATH_VALUES = __ENV.PATH_VALUES ? JSON.parse(__ENV.PATH_VALUES) : {json.dumps(path_values or {})};
const HEADERS = Object.assign({{'Content-Type': 'application/json'}}, {json.dumps(headers or {})},
  __ENV.AUTH_TOKEN ? {{Authorization: `Bearer ${{__ENV.AUTH_TOKEN}}`}} : {{}});

export const options = {json.dumps(opts, indent=2)};

const TOTAL = ENDPOINTS.reduce((s, e) => s + e.weight, 0);
function pick() {{
  let r = Math.random() * TOTAL;
  for (const e of ENDPOINTS) {{ r -= e.weight; if (r <= 0) return e; }}
  return ENDPOINTS[ENDPOINTS.length - 1];
}}
function fill(path) {{
  return path.replace(/\\{{([^}}:]+)(:[^}}]*)?\\}}/g, (_, name) => {{
    const v = PATH_VALUES[name];
    const value = Array.isArray(v) ? v[Math.floor(Math.random() * v.length)] : (v ?? '1');
    return encodeURIComponent(value);
  }});
}}

export default function () {{
  const e = pick();
  const params = {{ headers: HEADERS, tags: {{ name: `${{e.method}} ${{e.path}}` }} }};
  const body = e.body == null ? null : JSON.stringify(e.body);
  const res = http.request(e.method, BASE_URL + fill(e.path), body, params);
  check(res, {{ 'status < 400': (r) => r.status < 400 }});
  sleep({float(think_time)} * (0.5 + Math.random()));
}}
"""


def create(cfg: dict, name: str, service: str, endpoints: list, profile: str = "load", vus: int = 10, duration: str = "1m",
           base_url: str = "", **kw) -> dict:
    """Write <loadtest_dir>/<name>.js and register it under loadtests.<name> in config.json."""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,60}", name):
        raise ValueError("name: letters, digits, - and _ only")
    if service and service not in cfg["services"]:
        raise ValueError(f"unknown service: {service}")
    kw = {k: v for k, v in kw.items() if k in ("path_values", "headers", "p95_ms", "max_error_rate", "think_time")}
    base = base_url or (compose.service_base_url(service, cfg["services"][service], cfg) if service else "")
    if not base:
        raise ValueError("no base URL")
    script = generate(base, endpoints, profile, vus, duration, **kw)
    d = config.path(cfg, "loadtest_dir")
    d.mkdir(parents=True, exist_ok=True)
    f = d / f"{name}.js"
    f.write_text(script)
    cfg.setdefault("loadtests", {})[name] = {"script": f.name, "service": service or None, "profile": profile,
                                              "vus": int(vus), "duration": duration, "jfr": True, "env": {}}
    config.save(cfg)
    return {"name": name, "script": str(f), "profile": profile, "endpoints": len(endpoints)}


def script_path(cfg: dict, t: dict) -> Path:
    p = Path(t["script"]).expanduser()
    return p if p.is_absolute() else config.path(cfg, "loadtest_dir") / p
