"""Java Flight Recorder: automatic continuous recording for devctl-started JVMs, on-demand snapshots/timed recordings
for any local JVM, and analysis with the repository's JFR analyzer (scripts/jfr-analyze.sh, docs/tools/jfr-analyzer.md).

Layout: <builds_dir>/jfr/<service>/<file>.jfr, a sidecar <file>.jfr.meta.json, and the analysis in <file>.jfr.report/
(status.json, analyzer.log, report.html/.json/.xlsx/-summary.json).
"""
from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

from . import config, javahome, jvm

CONTINUOUS = "devctl"
REPO_ANALYZER = Path(__file__).resolve().parents[2] / "scripts" / "jfr-analyze.sh"
REPORT_FILES = {"report.html": "text/html; charset=utf-8", "report.json": "application/json",
                "report-summary.json": "application/json",
                "report.xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "analyzer.log": "text/plain; charset=utf-8"}


def settings(cfg: dict, svc: dict | None = None) -> dict:
    s = dict(cfg.get("jfr", {}))
    s.update((svc or {}).get("jfr", {}))
    return s


def root(cfg: dict) -> Path:
    return config.builds_root(cfg) / "jfr"


def service_dir(cfg: dict, service: str) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", service):
        raise ValueError(f"bad service name: {service}")
    d = root(cfg) / service
    d.mkdir(parents=True, exist_ok=True)
    return d


def jvm_flags(cfg: dict, name: str, svc: dict | None = None) -> str:
    """JVM options for a devctl-started JVM: the service marker and, when jfr.auto, a continuous recording that keeps
    the last ``maxage`` in a ring buffer (dumped on demand and on exit)."""
    s = settings(cfg, svc)
    flags = [f"{jvm.MARK}{name}"]
    if s.get("auto", True):
        d = service_dir(cfg, name)
        opts = f"name={CONTINUOUS},settings={s.get('settings', 'profile')},maxage={s.get('maxage', '30m')}," \
               f"maxsize={s.get('maxsize', '256m')},dumponexit=true,filename={d}/{name}-exit-%t.jfr"
        flags += [f"-XX:StartFlightRecording:{opts}", f"-XX:FlightRecorderOptions:stackdepth={int(s.get('stackdepth', 256))}"]
    return " ".join(shlex.quote(f) for f in flags)


def _target(cfg: dict, service: str = "", pid: int = 0) -> tuple:
    if pid:
        name = service or next((j["service"] for j in jvm.list_jvms(cfg) if j["pid"] == pid and j["service"]), None) or f"pid-{pid}"
        return int(pid), name
    if not service:
        raise ValueError("give a service or a pid")
    return jvm.find_pid(cfg, service), service


def _write_meta(path: Path, meta: dict) -> None:
    Path(str(path) + ".meta.json").write_text(json.dumps(meta, indent=2))


def snapshot(cfg: dict, service: str = "", pid: int = 0) -> dict:
    """Dump the continuous recording (the last jfr.maxage) right now."""
    pid, name = _target(cfg, service, pid)
    if not re.search(rf"name={CONTINUOUS}\b", jvm.jcmd(cfg, pid, "JFR.check").replace('"', "")):
        raise ValueError(f"{name} has no continuous '{CONTINUOUS}' recording (jfr.auto off or not started by devctl); "
                         f"use a timed recording instead")
    f = service_dir(cfg, name) / f"{name}-snapshot-{time.strftime('%Y%m%d-%H%M%S')}.jfr"
    jvm.jcmd(cfg, pid, "JFR.dump", f"name={CONTINUOUS}", f"filename={f}")
    _write_meta(f, {"service": name, "pid": pid, "kind": "snapshot", "created": time.time()})
    return _maybe_analyze(cfg, f, name)


def record(cfg: dict, seconds: int, service: str = "", pid: int = 0) -> dict:
    """Timed recording; the file appears when it ends (analysis then starts on the next listing if jfr.auto_analyze)."""
    seconds = max(5, min(int(seconds), 3600))
    pid, name = _target(cfg, service, pid)
    f = service_dir(cfg, name) / f"{name}-timed-{time.strftime('%Y%m%d-%H%M%S')}.jfr"
    s = settings(cfg, cfg["services"].get(name))
    jvm.jcmd(cfg, pid, "JFR.start", f"name=devctl-timed-{int(time.time())}", f"settings={s.get('settings', 'profile')}",
             f"duration={seconds}s", f"filename={f}")
    _write_meta(f, {"service": name, "pid": pid, "kind": "timed", "created": time.time(), "ends_at": time.time() + seconds})
    return {"recording": rel(cfg, f), "service": name, "pid": pid, "ready_in_seconds": seconds}


def start_named(cfg: dict, pid: int, rec_name: str) -> None:
    s = settings(cfg)
    jvm.jcmd(cfg, pid, "JFR.start", f"name={rec_name}", f"settings={s.get('settings', 'profile')}")


def stop_named(cfg: dict, pid: int, rec_name: str, service: str, kind: str, extra: dict | None = None) -> Path:
    f = service_dir(cfg, service) / f"{service}-{kind}-{time.strftime('%Y%m%d-%H%M%S')}.jfr"
    jvm.jcmd(cfg, pid, "JFR.stop", f"name={rec_name}", f"filename={f}", timeout=180)
    _write_meta(f, {"service": service, "pid": pid, "kind": kind, "created": time.time(), **(extra or {})})
    return f


def rel(cfg: dict, f: Path) -> str:
    return str(f.resolve().relative_to(root(cfg).resolve()))


def resolve(cfg: dict, rec_id: str) -> Path:
    f = (root(cfg) / rec_id).resolve()
    if root(cfg).resolve() not in f.parents or f.suffix != ".jfr":
        raise ValueError(f"bad recording id: {rec_id}")
    if not f.exists():
        raise ValueError(f"recording not found (yet): {rec_id}")
    return f


def report_dir(f: Path) -> Path:
    return Path(str(f) + ".report")


def analysis(f: Path) -> dict:
    st = report_dir(f) / "status.json"
    out = json.loads(st.read_text()) if st.exists() else {"status": "none"}
    summ = report_dir(f) / "report-summary.json"
    if summ.exists():
        try:
            s = json.loads(summ.read_text())
            ex = s.get("executiveSummary", {})
            out.update({"health": ex.get("status"), "score": ex.get("healthScore"), "headline": ex.get("headline")})
        except ValueError:
            pass
    return out


def list_recordings(cfg: dict, service: str = "") -> list:
    r = root(cfg)
    if not r.is_dir():
        return []
    out, now = [], time.time()
    for d in sorted(p for p in r.iterdir() if p.is_dir()):
        if service and d.name != service:
            continue
        for f in d.glob("*.jfr"):
            mp = Path(str(f) + ".meta.json")
            meta = json.loads(mp.read_text()) if mp.exists() else {"service": d.name, "kind": "exit" if "-exit-" in f.name else "file"}
            st = f.stat()
            if st.st_size == 0 or (meta.get("ends_at") and now < meta["ends_at"] + 2):
                continue  # still being written
            a = analysis(f)
            if a["status"] == "none" and settings(cfg, cfg["services"].get(d.name)).get("auto_analyze", True) \
                    and meta.get("kind") == "timed" and analyzer_cmd(cfg):
                a = analyze(cfg, rel(cfg, f))  # timed recordings finish asynchronously; analyze on first sight
            out.append({"id": rel(cfg, f), "service": meta.get("service", d.name), "kind": meta.get("kind"),
                        "file": f.name, "size": st.st_size, "created": st.st_mtime, "loadrun": meta.get("loadrun"),
                        "analysis": a})
    # pending timed recordings
    for mp in r.glob("*/*.jfr.meta.json"):
        f = Path(str(mp)[:-len(".meta.json")])
        meta = json.loads(mp.read_text())
        if meta.get("ends_at") and (not f.exists() or now < meta["ends_at"] + 2) and (not service or meta["service"] == service):
            out.append({"id": rel(cfg, f) if f.exists() else str(f.relative_to(r)), "service": meta["service"],
                        "kind": "timed", "file": f.name, "size": 0, "created": meta["created"], "pending": True,
                        "ready_in": max(0, int(meta["ends_at"] - now)), "analysis": {"status": "none"}})
    return sorted(out, key=lambda x: x["created"], reverse=True)


def analyzer_cmd(cfg: dict) -> list:
    cmd = settings(cfg).get("analyzer_cmd") or (str(REPO_ANALYZER) if REPO_ANALYZER.exists() else "")
    return shlex.split(cmd) if cmd else []


def packages(cfg: dict, service: str) -> list:
    return list(settings(cfg, cfg["services"].get(service)).get("packages", []))


def _maybe_analyze(cfg: dict, f: Path, service: str) -> dict:
    res = {"recording": rel(cfg, f), "service": service}
    if settings(cfg, cfg["services"].get(service)).get("auto_analyze", True) and analyzer_cmd(cfg):
        res["analysis"] = analyze(cfg, res["recording"])
    return res


def analyze(cfg: dict, rec_id: str, wait: bool = False) -> dict:
    """Start the analyzer as a detached process (results land in <file>.report/)."""
    f = resolve(cfg, rec_id)
    if not analyzer_cmd(cfg):
        raise ValueError("no JFR analyzer: set jfr.analyzer_cmd (default is this repo's scripts/jfr-analyze.sh)")
    rd = report_dir(f)
    rd.mkdir(exist_ok=True)
    st = {"status": "running", "started": time.time()}
    (rd / "status.json").write_text(json.dumps(st))
    pkg_root = str(Path(__file__).resolve().parent.parent)
    args = [sys.executable, "-m", "devctl", "_analyze-jfr", rec_id]
    if wait:
        return run_analysis(cfg, rec_id)
    with open(rd / "analyzer.log", "wb") as log:
        subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, start_new_session=True, cwd=pkg_root,
                         env={**os.environ, "PYTHONPATH": pkg_root})
    return st


def run_analysis(cfg: dict, rec_id: str) -> dict:
    f = resolve(cfg, rec_id)
    rd = report_dir(f)
    rd.mkdir(exist_ok=True)
    meta_p = Path(str(f) + ".meta.json")
    service = json.loads(meta_p.read_text())["service"] if meta_p.exists() else f.parent.name
    cmd = analyzer_cmd(cfg) + [str(f), "-o", str(rd), "-n", "report"]
    for p in packages(cfg, service):
        cmd += ["-p", p]
    env = dict(os.environ)
    jh = javahome.resolve(settings(cfg).get("analyzer_java_home", "auto:25"))
    if jh:
        env["JAVA_HOME"] = jh
    st = {"status": "running", "started": time.time()}
    (rd / "status.json").write_text(json.dumps(st))
    print(f"$ {shlex.join(cmd)}", flush=True)
    try:
        rc = subprocess.run(cmd, env=env, timeout=1800).returncode
        st.update(status="success" if rc == 0 else "failed", exit_code=rc)
    except Exception as e:  # missing JDK 25, timeout
        st.update(status="failed", error=str(e))
    st["finished"] = time.time()
    (rd / "status.json").write_text(json.dumps(st))
    return analysis(f)


def summary(cfg: dict, rec_id: str) -> dict:
    f = resolve(cfg, rec_id)
    p = report_dir(f) / "report-summary.json"
    if not p.exists():
        raise ValueError(f"no analysis summary yet for {rec_id} ({analysis(f)['status']})")
    return json.loads(p.read_text())


def report_file(cfg: dict, rec_id: str, name: str) -> tuple:
    if name not in REPORT_FILES:
        raise ValueError(f"unknown report file {name}")
    p = report_dir(resolve(cfg, rec_id)) / name
    if not p.exists():
        raise ValueError(f"{name} not available")
    return p.read_bytes(), REPORT_FILES[name]
