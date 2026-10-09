"""Generate the process-compose project from config + deployed state."""
from __future__ import annotations

import json
import shlex
from pathlib import Path

from . import config, infra, javahome

JBOSS = "jboss-eap"


def jboss_base(cfg: dict) -> Path:
    return config.builds_root(cfg) / "jboss" / "standalone"


def deploy_dir(cfg: dict, service: str) -> Path:
    return config.builds_root(cfg) / "deployed" / service


def _probe(h: dict | None, period=5, delay=3, failures=30) -> dict:
    if not h:
        return {}
    if "url" in h:
        from urllib.parse import urlparse
        u = urlparse(h["url"])
        hg = {"host": u.hostname or "127.0.0.1", "scheme": u.scheme, "path": u.path or "/", "port": str(u.port or 80)}
        return {"readiness_probe": {"http_get": hg, "initial_delay_seconds": delay, "period_seconds": period,
                                    "failure_threshold": failures}}
    return {"readiness_probe": {"exec": {"command": f"nc -z {h.get('host', '127.0.0.1')} {h['port']}"},
                                "initial_delay_seconds": delay, "period_seconds": period, "failure_threshold": failures}}


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


def local_services(cfg: dict) -> list:
    """Base URLs of the configured services, offered to the CEL faker as one-click Swagger import sources."""
    out = []
    for name, s in cfg["services"].items():
        if s.get("builtin"):
            continue
        kind = s.get("kind", "jar")
        if kind == "war":
            out.append({"name": name, "url": f"http://localhost:{8080 + cfg['jboss'].get('port_offset', 0)}/{s.get('context', name)}"})
        elif s.get("port"):
            out.append({"name": name, "url": f"http://localhost:{s['port']}"})
    return out


def _celfaker(cfg: dict, s: dict) -> tuple:
    """(command, extra environment) of the built-in CEL faker service."""
    jh = javahome.resolve(s.get("java_home") or cfg["java_home"])
    dash = cfg["dashboard_port"]
    env = [f"CELFAKER_SERVICES={json.dumps(local_services(cfg), separators=(',', ':'))}",
           f"CELFAKER_FRAME_ANCESTORS=http://127.0.0.1:{dash},http://localhost:{dash}"]
    path = 'PATH="$JAVA_HOME/bin:$PATH" ' if jh else ""
    if jh:
        env.append(f"JAVA_HOME={jh}")
    return f"{path}exec ./scripts/celfaker.sh serve --port {s['port']}", env


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
            "environment": [f"JAVA_HOME={jh}", f"JAVA_OPTS={jb.get('java_opts', '')}", "NOPAUSE=true"],
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
            cmd = f"exec {shlex.quote(jh + '/bin/java') if jh else 'java'} {s.get('java_opts', '')} -jar app.jar {s.get('args', '')}"
            wd = str(dd)
            active = name in deployed
        else:  # command
            cmd, wd, active = s["cmd"], str(Path(s.get("cwd", ".")).expanduser()), s.get("autostart", True)
            if s.get("builtin") == "celfaker":
                cmd, extra = _celfaker(cfg, s)
                env += extra
        deps = {d: {"condition": "process_healthy"} for d in s.get("depends_on", [])
                if d in procs and not procs[d].get("disabled") and "readiness_probe" in procs[d]}
        if s.get("needs_jboss") and JBOSS in procs:
            deps[JBOSS] = {"condition": "process_healthy"}
        p = {"command": cmd.strip(), "working_dir": wd, "environment": env, "namespace": "services",
             "availability": {"restart": "on_failure", "backoff_seconds": 5, "max_restarts": 3},
             # the first start of a built-in tool compiles it with Maven: allow ~10 minutes before the probe gives up
             **(_probe(service_health(name, s, cfg), 5, 5, 120) if s.get("builtin") else _probe(service_health(name, s, cfg))),
             **({"depends_on": deps} if deps else {})}
        if not active:
            p["disabled"] = True
        procs[name] = p
    return {"version": "0.5", "is_strict": False, "processes": procs}
