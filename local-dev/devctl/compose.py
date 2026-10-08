"""Generate the process-compose project from config + deployed state."""
from __future__ import annotations

import shlex
import sys
from pathlib import Path

from . import config, infra, javahome, jfr

JBOSS = "jboss-eap"
SHIPPER = "log-shipper"


def log_dir() -> Path:
    return config.HOME / "logs"


def jboss_base(cfg: dict) -> Path:
    return config.builds_root(cfg) / "jboss" / "standalone"


def deploy_dir(cfg: dict, service: str) -> Path:
    return config.builds_root(cfg) / "deployed" / service


def _probe(h: dict | None, period=5, delay=3) -> dict:
    if not h:
        return {}
    if "url" in h:
        from urllib.parse import urlparse
        u = urlparse(h["url"])
        hg = {"host": u.hostname or "127.0.0.1", "scheme": u.scheme, "path": u.path or "/", "port": str(u.port or 80)}
        return {"readiness_probe": {"http_get": hg, "initial_delay_seconds": delay, "period_seconds": period,
                                    "failure_threshold": 30}}
    return {"readiness_probe": {"exec": {"command": f"nc -z {h.get('host', '127.0.0.1')} {h['port']}"},
                                "initial_delay_seconds": delay, "period_seconds": period, "failure_threshold": 30}}


def service_health(name: str, s: dict, cfg: dict) -> dict | None:
    if s.get("health"):
        return s["health"]
    kind = s.get("kind", "jar")
    if kind == "jar" and s.get("port"):
        return {"url": f"http://localhost:{s['port']}/actuator/health"}
    if kind == "war":
        off = cfg["jboss"].get("port_offset", 0)
        return {"url": f"http://localhost:{8080 + off}/{s.get('context', name)}/"}
    return None


def service_base_url(name: str, s: dict, cfg: dict) -> str | None:
    """Root URL of the service's HTTP API (where /actuator/... and the app's endpoints live)."""
    if s.get("base_url"):
        return s["base_url"].rstrip("/")
    if s.get("kind", "jar") == "war":
        return f"http://localhost:{8080 + cfg['jboss'].get('port_offset', 0)}/{s.get('context', name)}"
    if s.get("port"):
        return f"http://localhost:{s['port']}"
    return None


def build_project(cfg: dict, st: dict) -> dict:
    procs: dict = {}
    deployed = st.get("deployed", {})
    wars = [n for n, s in cfg["services"].items() if s.get("kind") == "war"]
    live_wars = [n for n in wars if n in deployed]

    for name, meta in infra.catalog(cfg).items():
        enabled = name in cfg["infra"]["enabled"]
        p = {"command": meta["command"], "namespace": "infra", "shutdown": meta["shutdown"],
             "availability": {"restart": "on_failure", "backoff_seconds": 5, "max_restarts": 3}, **_probe(meta["health"], 5, 5)}
        if not enabled:
            p["disabled"] = True
        procs[name] = p

    jb = cfg["jboss"]
    if jb.get("home") and (live_wars or jb.get("always_on")):
        off = jb.get("port_offset", 0)
        jh = javahome.resolve(jb.get("java_home") or cfg["java_home"])
        procs[JBOSS] = {
            "command": f"exec {shlex.quote(str(Path(jb['home']).expanduser()))}/bin/standalone.sh "
                       f"-Djboss.server.base.dir={shlex.quote(str(jboss_base(cfg)))} "
                       f"-Djboss.socket.binding.port-offset={off} -b 0.0.0.0",
            "namespace": "jboss",
            "environment": [f"JAVA_HOME={jh}", f"JAVA_OPTS={jb.get('java_opts', '')} {jfr.jvm_flags(cfg, JBOSS, jb)}", "NOPAUSE=true"],
            "readiness_probe": {"http_get": {"host": "127.0.0.1", "scheme": "http", "path": "/health/ready",
                                             "port": str(9990 + off)},
                                "initial_delay_seconds": 15, "period_seconds": 5, "failure_threshold": 60},
            "shutdown": {"signal": 15, "timeout_seconds": 60},
            "availability": {"restart": "on_failure", "backoff_seconds": 5, "max_restarts": 2},
            "log_location": str(jboss_base(cfg) / "log" / "server.log"),
        }

    for name, s in cfg["services"].items():
        kind = s.get("kind", "jar")
        if kind == "war":
            continue  # hot-deployed into JBoss; shown in the dashboard, not a process of its own
        dd = deploy_dir(cfg, name)
        env = [f"{k}={v}" for k, v in s.get("env", {}).items()]
        if kind == "jar":
            jh = javahome.resolve(s.get("java_home") or cfg["java_home"])
            if s.get("port"):
                env.append(f"SERVER_PORT={s['port']}")
            env.append(f"JAVA_HOME={jh}")
            cmd = (f"exec {shlex.quote(jh + '/bin/java') if jh else 'java'} {jfr.jvm_flags(cfg, name, s)} "
                   f"{s.get('java_opts', '')} -jar app.jar {s.get('args', '')}")
            wd = str(dd)
            active = name in deployed
        else:  # command
            cmd, wd, active = s["cmd"], str(Path(s.get("cwd", ".")).expanduser()), True
        deps = {d: {"condition": "process_healthy"} for d in s.get("depends_on", [])
                if d in procs and not procs[d].get("disabled") and "readiness_probe" in procs[d]}
        if s.get("needs_jboss") and JBOSS in procs:
            deps[JBOSS] = {"condition": "process_healthy"}
        p = {"command": cmd.strip(), "working_dir": wd, "environment": env, "namespace": "services",
             "availability": {"restart": "on_failure", "backoff_seconds": 5, "max_restarts": 3},
             **_probe(service_health(name, s, cfg)), **({"depends_on": deps} if deps else {})}
        if not active:
            p["disabled"] = True
        procs[name] = p
    if cfg["infra"].get("ship_logs", True) and "loki" in cfg["infra"]["enabled"]:
        root = Path(__file__).resolve().parent.parent
        procs[SHIPPER] = {"command": f"PYTHONPATH={shlex.quote(str(root))} exec {shlex.quote(sys.executable)} -m devctl ship-logs",
                          "namespace": "infra", "depends_on": {"loki": {"condition": "process_healthy"}},
                          "availability": {"restart": "always", "backoff_seconds": 5}}
    for name, p in procs.items():
        p.setdefault("log_location", str(log_dir() / f"{name}.log"))
    return {"version": "0.5", "is_strict": False, "processes": procs}

