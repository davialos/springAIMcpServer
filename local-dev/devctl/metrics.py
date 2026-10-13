"""Live service metrics, read continuously from the services themselves.

Sources, best first (detected per service, re-detected while failing):
  prometheus  GET <base>/actuator/prometheus   (spring-boot-starter-actuator + micrometer-registry-prometheus)
  actuator    GET <base>/actuator/metrics/...  (only spring-boot-starter-actuator; no per-endpoint table)
  probe       GET <health url>                 (anything; latency + up only)
Each tick turns two cumulative readings into rates: requests/s, avg + p95 latency (p95 needs
management.metrics.distribution.percentiles-histogram.http.server.requests=true), 5xx %, heap, CPU, threads,
GC time, connection pool, Tomcat busy threads, error log lines.
"""
from __future__ import annotations

import json
import math
import re
import threading
import time
import urllib.error
import urllib.request
from collections import deque

from . import compose, config, health

_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)')
_LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')


def parse_prom(text: str) -> list:
    out = []
    for line in text.splitlines():
        if not line or line[0] == "#":
            continue
        m = _LINE.match(line)
        if not m:
            continue
        try:
            v = float(m.group(4))
        except ValueError:
            continue
        labels = dict(_LABEL.findall(m.group(3) or ""))
        out.append((m.group(1), labels, v))
    return out


def _is_app_uri(labels: dict) -> bool:
    uri = labels.get("uri", "")
    return not uri.startswith("/actuator") and uri not in ("/error",)


def extract_prom(samples: list) -> dict:
    """Cumulative counters + gauges from Micrometer's Prometheus output."""
    r = {"req_count": 0.0, "req_sum": 0.0, "err_count": 0.0, "buckets": {}, "endpoints": {}, "heap_used": 0.0,
         "heap_max": 0.0, "gc_sum": 0.0, "log_errors": 0.0}
    seen = set()
    for name, lb, v in samples:
        base = name
        if base.startswith("http_server_requests_seconds"):
            if not _is_app_uri(lb):
                continue
            key = f"{lb.get('method', '?')} {lb.get('uri', '?')}"
            ep = r["endpoints"].setdefault(key, {"count": 0.0, "sum": 0.0, "max": 0.0, "err": 0.0})
            if name.endswith("_count"):
                r["req_count"] += v
                ep["count"] += v
                if lb.get("status", "").startswith("5"):
                    r["err_count"] += v
                    ep["err"] += v
            elif name.endswith("_sum"):
                r["req_sum"] += v
                ep["sum"] += v
            elif name.endswith("_max"):
                ep["max"] = max(ep["max"], v)
            elif name.endswith("_bucket"):
                le = math.inf if lb.get("le") in ("+Inf", "Inf") else float(lb.get("le", "nan"))
                r["buckets"][le] = r["buckets"].get(le, 0.0) + v
            seen.add("http")
        elif name == "jvm_memory_used_bytes" and lb.get("area") == "heap":
            r["heap_used"] += v
        elif name == "jvm_memory_max_bytes" and lb.get("area") == "heap" and v > 0:
            r["heap_max"] += v
        elif name in ("base_memory_usedHeap_bytes",):
            r["heap_used"] = v
        elif name in ("base_memory_maxHeap_bytes",):
            r["heap_max"] = v
        elif name == "process_cpu_usage":
            r["cpu"] = v
        elif name in ("jvm_threads_live_threads", "jvm_threads_live", "base_thread_count"):
            r["threads"] = v
        elif name == "jvm_gc_pause_seconds_sum":
            r["gc_sum"] += v
        elif name == "hikaricp_connections_active":
            r["pool_active"] = r.get("pool_active", 0.0) + v
        elif name == "hikaricp_connections_pending":
            r["pool_pending"] = r.get("pool_pending", 0.0) + v
        elif name == "hikaricp_connections_max":
            r["pool_max"] = r.get("pool_max", 0.0) + v
        elif name in ("tomcat_threads_busy_threads", "tomcat_threads_busy"):
            r["tomcat_busy"] = r.get("tomcat_busy", 0.0) + v
        elif name in ("logback_events_total", "log4j2_events_total") and lb.get("level") == "error":
            r["log_errors"] += v
    r["has_http"] = "http" in seen
    r["has_histogram"] = bool(r["buckets"])
    return r


def quantile_from_buckets(prev: dict, cur: dict, q: float) -> float | None:
    """Histogram quantile over the interval (Prometheus histogram_quantile on bucket deltas), seconds."""
    les = sorted(cur)
    deltas = [(le, cur[le] - prev.get(le, 0.0)) for le in les]
    total = deltas[-1][1] if deltas else 0
    if total <= 0:
        return None
    rank, lo_le, lo_c = q * total, 0.0, 0.0
    for le, c in deltas:
        if c >= rank:
            if math.isinf(le):
                return lo_le
            return lo_le + (le - lo_le) * ((rank - lo_c) / (c - lo_c) if c > lo_c else 1)
        lo_le, lo_c = le, c
    return None


class Sampler:
    """Turns successive readings of one service into points. Not thread-safe; one per service."""

    def __init__(self, name: str, base_url: str | None, health_cfg: dict | None, prom_url: str | None = None):
        self.name, self.base, self.health = name, (base_url or "").rstrip("/"), health_cfg
        self.prom_url = prom_url
        self.mode, self.prev, self.prev_t, self.next_detect = None, None, 0.0, 0.0
        self.endpoints: list = []

    def _get(self, url: str, timeout: float = 3.0) -> bytes:
        req = urllib.request.Request(url, headers={"User-Agent": "devctl", "Accept": "text/plain, application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read(20_000_000)

    def _detect(self) -> None:
        self.mode = "probe"
        candidates = [("prometheus", self.prom_url)] if self.prom_url else []
        if self.base:
            candidates += [("prometheus", self.base + "/actuator/prometheus"), ("actuator", self.base + "/actuator/metrics")]
        for mode, url in candidates:
            try:
                body = self._get(url)
                if mode == "prometheus" and b"# TYPE" not in body[:200000] and b"_" not in body[:1000]:
                    continue
                self.mode, self.url = mode, url
                return
            except Exception:
                continue

    def _actuator(self) -> dict:
        base = self.url.rstrip("/")

        def m(name, tag=None):
            try:
                q = f"?tag={urllib.request.quote(tag)}" if tag else ""
                data = json.loads(self._get(f"{base}/{name}{q}"))
                return {x["statistic"]: x["value"] for x in data.get("measurements", [])}
            except Exception:
                return {}
        hr = m("http.server.requests")
        r = {"req_count": hr.get("COUNT", 0.0), "req_sum": hr.get("TOTAL_TIME", 0.0), "buckets": {}, "endpoints": {},
             "err_count": m("http.server.requests", "outcome:SERVER_ERROR").get("COUNT", 0.0),
             "heap_used": m("jvm.memory.used", "area:heap").get("VALUE", 0.0),
             "heap_max": m("jvm.memory.max", "area:heap").get("VALUE", 0.0),
             "gc_sum": m("jvm.gc.pause").get("TOTAL_TIME", 0.0),
             "log_errors": m("logback.events", "level:error").get("COUNT", 0.0), "has_http": bool(hr), "has_histogram": False}
        for key, metric in (("cpu", "process.cpu.usage"), ("threads", "jvm.threads.live"),
                            ("pool_active", "hikaricp.connections.active"), ("pool_pending", "hikaricp.connections.pending"),
                            ("tomcat_busy", "tomcat.threads.busy")):
            v = m(metric).get("VALUE")
            if v is not None:
                r[key] = v
        return r

    def sample(self) -> dict:
        now = time.time()
        if self.mode is None or (self.mode == "probe" and now >= self.next_detect):
            self._detect()
            self.next_detect = now + 30
        point = {"t": round(now, 3)}
        h = health.check(self.health) if self.health else {"ok": None}
        point["up"] = h.get("ok")
        point["probe_ms"] = h.get("ms") if h.get("ok") is not None else None
        if self.mode in ("prometheus", "actuator"):
            try:
                cur = extract_prom(parse_prom(self._get(self.url).decode("utf-8", "replace"))) \
                    if self.mode == "prometheus" else self._actuator()
            except Exception:
                self.mode, self.prev = None, None  # service restarted or went away: re-detect next tick
                point["source"] = "down"
                return point
            point["source"] = self.mode
            point.update(self._derive(cur, now))
            self.prev, self.prev_t = cur, now
        else:
            point["source"] = "probe"
        return point

    def _derive(self, cur: dict, now: float) -> dict:
        p = {"heap_mb": round(cur["heap_used"] / 1048576, 1),
             "heap_max_mb": round(cur["heap_max"] / 1048576, 1) if cur["heap_max"] else None}
        for k, f in (("cpu_pct", lambda: round(cur["cpu"] * 100, 1)), ("threads", lambda: int(cur["threads"])),
                     ("pool_active", lambda: cur["pool_active"]), ("pool_pending", lambda: cur["pool_pending"]),
                     ("tomcat_busy", lambda: cur["tomcat_busy"])):
            try:
                p[k] = f()
            except KeyError:
                pass
        prev, dt = self.prev, now - self.prev_t
        if not prev or dt <= 0 or cur["req_count"] < prev["req_count"]:  # first reading or counter reset (restart)
            return p
        dc = cur["req_count"] - prev["req_count"]
        p["rps"] = round(dc / dt, 2)
        p["avg_ms"] = round((cur["req_sum"] - prev["req_sum"]) / dc * 1000, 1) if dc > 0 else None
        p["err_pct"] = round((cur["err_count"] - prev["err_count"]) / dc * 100, 2) if dc > 0 else 0.0
        if cur["has_histogram"]:
            for q, key in ((0.95, "p95_ms"), (0.99, "p99_ms")):
                v = quantile_from_buckets(prev["buckets"], cur["buckets"], q)
                p[key] = round(v * 1000, 1) if v is not None else None
        p["gc_ms_s"] = round((cur["gc_sum"] - prev["gc_sum"]) / dt * 1000, 2)
        p["err_logs_min"] = round((cur["log_errors"] - prev["log_errors"]) / dt * 60, 1)
        eps = []
        for k, e in cur["endpoints"].items():
            pe = prev["endpoints"].get(k, {"count": 0.0, "sum": 0.0, "err": 0.0})
            c = e["count"] - pe["count"]
            eps.append({"endpoint": k, "rps": round(c / dt, 2), "avg_ms": round((e["sum"] - pe["sum"]) / c * 1000, 1) if c > 0 else None,
                        "max_ms": round(e["max"] * 1000, 1), "errors": int(e["err"] - pe["err"]), "total": int(e["count"])})
        self.endpoints = sorted(eps, key=lambda x: (-x["rps"], -x["total"]))[:30]
        return p


def sampler_for(cfg: dict, name: str) -> Sampler:
    s = cfg["services"][name]
    m = s.get("metrics", {})
    return Sampler(name, compose.service_base_url(name, s, cfg), compose.service_health(name, s, cfg), m.get("prometheus"))


def summarize(points: list) -> dict:
    """Peak / average figures over a list of points (used for load-test results)."""
    def vals(k):
        return [p[k] for p in points if p.get(k) is not None]
    out = {}
    for k in ("rps", "avg_ms", "p95_ms", "p99_ms", "err_pct", "heap_mb", "cpu_pct", "threads", "gc_ms_s", "pool_pending", "probe_ms"):
        v = vals(k)
        if v:
            out[k] = {"avg": round(sum(v) / len(v), 2), "max": max(v)}
    return out


class Collector:
    """Background sampling of every configured service, kept in memory (dashboard process only)."""

    def __init__(self, interval: float = 2.0, history: int = 900):
        self.interval, self.history = interval, history
        self.series: dict = {}
        self.samplers: dict = {}
        self.lock = threading.Lock()
        self.thread = None
        self.last_view = 0.0

    def ensure_started(self) -> None:
        self.last_view = time.time()
        if self.thread is None or not self.thread.is_alive():
            self.thread = threading.Thread(target=self._loop, daemon=True, name="devctl-metrics")
            self.thread.start()

    def _loop(self) -> None:
        while True:
            t0 = time.time()
            cfg = config.load()
            for name in cfg["services"]:
                if name not in self.samplers:
                    self.samplers[name] = sampler_for(cfg, name)
                try:
                    pt = self.samplers[name].sample()
                except Exception as e:  # never kill the loop
                    pt = {"t": time.time(), "source": "error", "error": str(e)}
                with self.lock:
                    self.series.setdefault(name, deque(maxlen=self.history)).append(pt)
            for gone in set(self.samplers) - set(cfg["services"]):
                self.samplers.pop(gone, None)
            # sample slowly when nobody looked for 5 minutes
            idle = time.time() - self.last_view > 300
            time.sleep(max(0.2, (self.interval * (5 if idle else 1)) - (time.time() - t0)))

    def get(self, name: str, since: float = 0.0) -> dict:
        with self.lock:
            pts = [p for p in self.series.get(name, ()) if p["t"] > since]
        s = self.samplers.get(name)
        return {"service": name, "points": pts, "source": s.mode if s else None,
                "endpoints": s.endpoints if s else [], "interval": self.interval}


def snapshot(cfg: dict, name: str, window: float = 2.0) -> dict:
    """One-shot reading (two samples ``window`` seconds apart) for the CLI / MCP, which have no collector."""
    if name not in cfg["services"]:
        raise ValueError(f"unknown service: {name}")
    s = sampler_for(cfg, name)
    s.sample()
    time.sleep(window)
    pt = s.sample()
    return {"service": name, "source": s.mode, "now": pt, "endpoints": s.endpoints[:10]}


def readiness(cfg: dict, name: str) -> list:
    """What the service exposes for observability/load testing, and the minimal change for what is missing."""
    if name not in cfg["services"]:
        raise ValueError(f"unknown service: {name}")
    s = cfg["services"][name]
    base = compose.service_base_url(name, s, cfg)
    checks = []

    def probe(path):
        if not base:
            return None, "no base URL (set services.<name>.base_url or port)"
        try:
            req = urllib.request.Request(base.rstrip("/") + path, headers={"User-Agent": "devctl"})
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.read(3_000_000), f"HTTP {r.status}"
        except urllib.error.HTTPError as e:
            return None, f"HTTP {e.code}"
        except Exception as e:
            return None, type(e).__name__
    body, d = probe("/actuator/health")
    checks.append({"check": "Actuator health", "ok": body is not None, "detail": d,
                   "fix": "add dependency org.springframework.boot:spring-boot-starter-actuator"})
    prom, d = probe("/actuator/prometheus")
    checks.append({"check": "Prometheus metrics", "ok": prom is not None, "detail": d,
                   "fix": "add io.micrometer:micrometer-registry-prometheus and "
                          "management.endpoints.web.exposure.include=health,info,metrics,prometheus"})
    hist = prom is not None and b"http_server_requests_seconds_bucket" in prom
    checks.append({"check": "Latency histogram (p95/p99)", "ok": hist, "detail": "buckets present" if hist else "no buckets yet (send a request) or disabled",
                   "fix": "management.metrics.distribution.percentiles-histogram.http.server.requests=true"})
    body, d = probe("/actuator/metrics")
    checks.append({"check": "Actuator metrics API (fallback)", "ok": body is not None, "detail": d,
                   "fix": "management.endpoints.web.exposure.include=...,metrics"})
    api, d = probe("/v3/api-docs")
    maps, d2 = probe("/actuator/mappings")
    checks.append({"check": "API discovery for load tests", "ok": api is not None or maps is not None,
                   "detail": "OpenAPI" if api is not None else ("actuator mappings" if maps is not None else f"{d} / {d2}"),
                   "fix": "add org.springdoc:springdoc-openapi-starter-webmvc-ui, or expose the 'mappings' endpoint"})
    return checks
