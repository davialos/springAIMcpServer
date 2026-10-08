"""One load-test run, end to end, as a detached ``devctl _run-loadtest`` process (like builds):

  JFR start on the target JVM -> k6 run (summary export; Prometheus remote-write when enabled) while the target's
  metrics are sampled every 2 s into timeline.json -> JFR stop -> analysis -> meta.json with k6 + service results.

Layout: <builds_dir>/loadruns/<id>/{meta.json, run.log, k6-summary.json, timeline.json, stop}
"""
from __future__ import annotations

import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

from . import config, jfr, jvm, loadgen, metrics

RUNNING, PASSED, THRESHOLDS, FAILED, STOPPED = "running", "passed", "thresholds_failed", "failed", "stopped"


def runs_dir(cfg: dict) -> Path:
    return config.builds_root(cfg) / "loadruns"


def _dir(cfg: dict, run_id: str) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", run_id):
        raise ValueError("bad run id")
    d = runs_dir(cfg) / run_id
    if not (d / "meta.json").exists():
        raise ValueError(f"unknown load run: {run_id}")
    return d


def read(cfg: dict, run_id: str) -> dict:
    return json.loads((_dir(cfg, run_id) / "meta.json").read_text())


def _save(d: Path, meta: dict) -> None:
    tmp = d / "meta.tmp"
    tmp.write_text(json.dumps(meta, indent=2))
    tmp.replace(d / "meta.json")


def _seconds(dur: str) -> float:
    m = re.fullmatch(r"(\d+)(ms|s|m|h)", dur or "")
    return int(m.group(1)) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600}[m.group(2)] if m else 0


def start(cfg: dict, name: str, vus: int = 0, duration: str = "", profile_note: str = "") -> dict:
    t = cfg.get("loadtests", {}).get(name)
    if not t:
        raise ValueError(f"unknown load test: {name} (known: {', '.join(cfg.get('loadtests', {})) or 'none'})")
    if not shutil.which("k6"):
        raise ValueError("k6 not found - brew install k6")
    if not loadgen.script_path(cfg, t).exists():
        raise ValueError(f"k6 script not found: {loadgen.script_path(cfg, t)}")
    if duration and not re.fullmatch(r"\d+(ms|s|m|h)", duration):
        raise ValueError("duration like 30s, 5m")
    for r in list_runs(cfg, 50):
        if r["test"] == name and r["status"] == RUNNING:
            raise ValueError(f"{name} is already running ({r['id']})")
    run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{name}"
    d = runs_dir(cfg) / run_id
    d.mkdir(parents=True)
    meta = {"id": run_id, "test": name, "service": t.get("service"), "profile": t.get("profile"), "status": RUNNING,
            "phase": "starting", "started": time.time(), "finished": None, "vus": int(vus or t.get("vus") or 0) or None,
            "duration": duration or t.get("duration"), "jfr": None, "k6": None, "service_summary": None, "error": None}
    stages = None
    try:
        stages = _stages_seconds(loadgen.script_path(cfg, t).read_text()) if not duration else None
    except OSError:
        pass
    meta["expected_seconds"] = stages or _seconds(meta["duration"] or "")
    _save(d, meta)
    root = str(Path(__file__).resolve().parent.parent)
    with open(d / "run.log", "ab") as log:
        p = subprocess.Popen([sys.executable, "-m", "devctl", "_run-loadtest", run_id], stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True, cwd=root, env={**os.environ, "PYTHONPATH": root})
    meta["runner_pid"] = p.pid
    _save(d, meta)
    return meta


def _stages_seconds(script: str) -> float:
    total = sum(_seconds(m) for m in re.findall(r'"duration":\s*"(\d+(?:ms|s|m|h))"', script.split("export default")[0]))
    return total


def stop(cfg: dict, run_id: str) -> dict:
    d = _dir(cfg, run_id)
    (d / "stop").write_text(str(time.time()))
    return {"id": run_id, "stopping": True}


def list_runs(cfg: dict, limit: int = 30) -> list:
    r = runs_dir(cfg)
    if not r.is_dir():
        return []
    out = []
    for d in sorted(r.iterdir(), reverse=True):
        try:
            m = json.loads((d / "meta.json").read_text())
        except (OSError, ValueError):
            continue
        if m["status"] == RUNNING and m.get("runner_pid") and not _alive(m["runner_pid"]):
            m["status"], m["error"] = FAILED, "runner process died"
            _save(d, m)
        out.append(m)
        if len(out) >= limit:
            break
    return out


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def timeline(cfg: dict, run_id: str) -> list:
    p = _dir(cfg, run_id) / "timeline.json"
    try:
        return json.loads(p.read_text()) if p.exists() else []
    except ValueError:
        return []


def log(cfg: dict, run_id: str, offset: int = 0) -> dict:
    p = _dir(cfg, run_id) / "run.log"
    with open(p, "rb") as f:
        f.seek(offset)
        data = f.read(200_000)
    return {"text": data.decode("utf-8", "replace"), "offset": offset + len(data)}


def k6_results(summary: dict) -> dict:
    """The figures worth showing from k6's --summary-export JSON."""
    m = summary.get("metrics", {})
    dur, reqs = m.get("http_req_duration", {}), m.get("http_reqs", {})
    failed, checks = m.get("http_req_failed", {}), m.get("checks", {})

    def rate(x):
        if "value" in x:
            return x["value"]
        if "rate" in x:
            return x["rate"]
        tot = x.get("passes", 0) + x.get("fails", 0)
        return x.get("passes", 0) / tot if tot else None
    out = {"requests": reqs.get("count"), "rps": reqs.get("rate"), "iterations": m.get("iterations", {}).get("count"),
           "vus_max": m.get("vus_max", {}).get("value") or m.get("vus_max", {}).get("max"),
           "latency_ms": {k: dur.get(k) for k in ("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)") if k in dur},
           "error_rate": rate(failed) if failed else None, "checks_rate": rate(checks) if checks else None,
           "data_received": m.get("data_received", {}).get("count"), "data_sent": m.get("data_sent", {}).get("count")}
    th = []
    for metric, body in m.items():
        for expr in (body.get("thresholds") or {}):
            th.append(f"{metric}: {expr}")
    out["thresholds"] = th
    return out


# ---------------------------------------------------------------- runner (detached process)

def run(cfg: dict, run_id: str) -> int:
    d = _dir(cfg, run_id)
    meta = read(cfg, run_id)
    t = cfg["loadtests"][meta["test"]]
    svc = meta.get("service")
    pid, rec, final = None, f"devctl-lt-{int(time.time())}", FAILED
    timeline_pts: list = []
    stop_sampling = threading.Event()

    def phase(p):
        meta["phase"] = p
        _save(d, meta)
        print(f"== {p}", flush=True)
    try:
        # 1. JFR
        if svc and t.get("jfr", True):
            try:
                pid = jvm.find_pid(cfg, svc)
                jfr.start_named(cfg, pid, rec)
                meta["jfr"] = {"status": "recording", "pid": pid}
                phase(f"JFR recording started on {svc} (pid {pid})")
            except Exception as e:
                meta["jfr"] = {"status": "skipped", "reason": str(e)}
                print(f"JFR skipped: {e}", flush=True)
        # 2. metrics sampling
        if svc and svc in cfg["services"]:
            sampler = metrics.sampler_for(cfg, svc)

            def sample_loop():
                while not stop_sampling.is_set():
                    try:
                        timeline_pts.append(sampler.sample())
                        (d / "timeline.json").write_text(json.dumps(timeline_pts))
                    except Exception as e:
                        print(f"sampling: {e}", flush=True)
                    stop_sampling.wait(2.0)
            threading.Thread(target=sample_loop, daemon=True).start()
        # 3. k6
        cmd = ["k6", "run", "--summary-export", str(d / "k6-summary.json"), "--tag", f"testid={meta['test']}",
               "--summary-trend-stats", "avg,min,med,max,p(90),p(95),p(99)"]
        env = dict(os.environ)
        if "prometheus" in cfg["infra"]["enabled"]:
            cmd += ["-o", "experimental-prometheus-rw"]
            env.update(K6_PROMETHEUS_RW_SERVER_URL="http://localhost:9090/api/v1/write",
                       K6_PROMETHEUS_RW_TREND_STATS="avg,p(95),p(99),max")
        if meta.get("vus") and meta["vus"] != t.get("vus"):
            cmd += ["--vus", str(meta["vus"])]
        if meta.get("duration") and meta["duration"] != t.get("duration"):
            cmd += ["--duration", meta["duration"]]
        for k, v in t.get("env", {}).items():
            cmd += ["-e", f"{k}={v}"]
        cmd.append(str(loadgen.script_path(cfg, t)))
        phase("k6 running")
        print("$ " + " ".join(cmd), flush=True)
        p = subprocess.Popen(cmd, cwd=str(config.path(cfg, "loadtest_dir")), env=env)
        stopped = False
        while p.poll() is None:
            if (d / "stop").exists() and not stopped:
                print("== stop requested: interrupting k6 (it still writes its summary)", flush=True)
                p.send_signal(signal.SIGINT)
                stopped = True
            time.sleep(0.5)
        rc = p.returncode
        meta["k6_exit_code"] = rc
        if (d / "k6-summary.json").exists():
            meta["k6"] = k6_results(json.loads((d / "k6-summary.json").read_text()))
        final = STOPPED if stopped else PASSED if rc == 0 else THRESHOLDS if rc == 99 else FAILED
    except Exception as e:
        final, meta["error"] = FAILED, str(e)
        print(f"LOAD RUN FAILED: {e}", flush=True)
    finally:
        stop_sampling.set()
        time.sleep(0.1)
        meta["service_summary"] = metrics.summarize(timeline_pts) if timeline_pts else None
        if timeline_pts:
            (d / "timeline.json").write_text(json.dumps(timeline_pts))
        # 4. JFR stop + analysis
        if pid and (meta.get("jfr") or {}).get("status") == "recording":
            try:
                phase("stopping JFR")
                f = jfr.stop_named(cfg, pid, rec, svc, "loadtest", {"loadrun": run_id})
                meta["jfr"] = {"status": "recorded", "recording": jfr.rel(cfg, f), "pid": pid}
                if jfr.settings(cfg, cfg["services"].get(svc)).get("auto_analyze", True) and jfr.analyzer_cmd(cfg):
                    phase("analyzing JFR")
                    meta["jfr"]["analysis"] = jfr.run_analysis(cfg, meta["jfr"]["recording"])
            except Exception as e:
                meta["jfr"] = {"status": "failed", "reason": str(e)}
                print(f"JFR stop/analysis failed: {e}", flush=True)
        # the status only turns final once JFR and its analysis are done, so readers never see a half-finished run
        meta["status"], meta["phase"], meta["finished"] = final, "done", time.time()
        _save(d, meta)
        print(f"== {final}", flush=True)
    return 0 if meta["status"] in (PASSED, STOPPED) else 1
