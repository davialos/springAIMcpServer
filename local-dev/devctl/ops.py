"""Operations shared by the CLI, the dashboard and the MCP server."""
from __future__ import annotations

import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import builds, compose, config, health, pc, repos


def _svc(cfg: dict, name: str) -> dict:
    if name not in cfg["services"]:
        raise ValueError(f"unknown service: {name} (known: {', '.join(cfg['services']) or 'none'})")
    return cfg["services"][name]


def _ensure_jboss_base(cfg: dict) -> Path:
    base = compose.jboss_base(cfg)
    home = Path(cfg["jboss"].get("home", "")).expanduser()
    if not (home / "bin" / "standalone.sh").exists():
        raise ValueError(f"jboss.home is not a JBoss EAP install: '{home}'")
    if not base.exists():
        base.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(home / "standalone", base, ignore=shutil.ignore_patterns("log", "data", "tmp", "deployments"))
        (base / "deployments").mkdir(exist_ok=True)
    return base


def deploy(cfg: dict, service: str, build_id: str, restart: bool = True) -> dict:
    s = _svc(cfg, service)
    kind = s.get("kind", "jar")
    if kind == "command":
        raise ValueError(f"{service} is a plain command service; nothing to deploy")
    src = builds.pick_artifact(cfg, build_id, s.get("artifact", "*." + kind))
    meta = builds.read_meta(cfg, build_id)
    if s.get("repo") and s["repo"] != meta["repo"]:
        raise ValueError(f"{service} belongs to repo {s['repo']}, build {build_id} is from {meta['repo']}")
    if kind == "war":
        dest = _ensure_jboss_base(cfg) / "deployments" / f"{s.get('context', service)}.war"
    else:
        dd = compose.deploy_dir(cfg, service)
        dd.mkdir(parents=True, exist_ok=True)
        dest = dd / "app.jar"
    tmp = dest.with_name(dest.name + ".part")
    shutil.copy2(src, tmp)
    tmp.replace(dest)
    st = config.state()
    st.setdefault("deployed", {})[service] = {"build_id": build_id, "branch": meta["branch"], "commit": meta["commit"],
                                              "artifact": src.name, "at": time.time()}
    config.save_state(st)
    msg = pc.sync(cfg) if pc.available() else "process-compose not installed; files deployed only"
    if restart and pc.available() and kind == "jar":
        _settle()
        pc.control(cfg, "restart", service) if pc.processes(cfg).get(service, {}).get("status") == "Running" \
            else pc.control(cfg, "start", service)
    elif restart and pc.available() and kind == "war" and compose.JBOSS in pc.processes(cfg) \
            and pc.processes(cfg)[compose.JBOSS]["status"] != "Running":
        _settle()
        pc.control(cfg, "start", compose.JBOSS)
    return {"service": service, "build": build_id, "branch": meta["branch"], "target": str(dest), "project": msg}


def _settle() -> None:
    time.sleep(1.5)  # let process-compose apply the project update before addressing the process


def deploy_latest(cfg: dict, service: str, branch: str = "") -> dict:
    s = _svc(cfg, service)
    for b in builds.list_builds(cfg, limit=200, repo=s.get("repo", ""), only_success=True):
        if not branch or b["branch"] == branch:
            return deploy(cfg, service, b["id"])
    raise ValueError(f"no successful build for repo {s.get('repo')} branch {branch or '*'}; build it first")


def undeploy(cfg: dict, service: str) -> dict:
    s = _svc(cfg, service)
    st = config.state()
    if service not in st.get("deployed", {}):
        raise ValueError(f"{service} is not deployed")
    if s.get("kind") == "war":
        base = compose.jboss_base(cfg) / "deployments"
        for f in base.glob(f"{s.get('context', service)}.war*"):
            f.unlink()
    else:
        if pc.available() and pc.is_up(cfg):
            try:
                pc.control(cfg, "stop", service)
            except pc.PcError:
                pass
        shutil.rmtree(compose.deploy_dir(cfg, service), ignore_errors=True)
    del st["deployed"][service]
    config.save_state(st)
    if pc.available() and pc.is_up(cfg):
        pc.sync(cfg)
    return {"service": service, "undeployed": True}


def service_control(cfg: dict, service: str, action: str) -> str:
    s = _svc(cfg, service)
    target = compose.JBOSS if s.get("kind") == "war" else service
    if action == "start" and service not in config.state().get("deployed", {}) and s.get("kind", "jar") != "command":
        raise ValueError(f"{service} has no deployed build; deploy one first")
    return pc.control(cfg, action, target)


def stack_control(cfg: dict, stack: str, action: str) -> list:
    if stack not in cfg["stacks"]:
        raise ValueError(f"unknown stack: {stack}")
    members = cfg["stacks"][stack]
    res, done = [], set()
    for svc in (members if action != "stop" else list(reversed(members))):
        target = compose.JBOSS if cfg["services"][svc].get("kind") == "war" else svc
        if target in done:
            continue
        done.add(target)
        try:
            res.append(service_control(cfg, svc, "start" if action == "up" else "stop"))
        except Exception as e:
            res.append(f"{svc}: {e}")
    return res


def infra_control(cfg: dict, name: str, action: str) -> str:
    cat = {"prometheus", "loki", "grafana", "postgres"}
    if name not in cat:
        raise ValueError(f"unknown infra service: {name}")
    if action in ("start", "stop", "restart"):
        en = cfg["infra"]["enabled"]
        if action != "stop" and name not in en:
            en.append(name)
            config.save(cfg)
            pc.sync(cfg)
            _settle()
        return pc.control(cfg, action, name)
    raise ValueError(action)


def status(cfg: dict, with_health: bool = True) -> dict:
    st = config.state().get("deployed", {})
    procs = pc.processes(cfg) if pc.available() else {}
    names = list(cfg["services"])
    health_cfg = {n: compose.service_health(n, cfg["services"][n], cfg) for n in names}
    if with_health and names:
        with ThreadPoolExecutor(max_workers=8) as ex:
            hs = dict(zip(names, ex.map(lambda n: health.check(health_cfg[n]), names)))
    else:
        hs = {}
    services = {}
    for n in names:
        s = cfg["services"][n]
        proc = procs.get(compose.JBOSS if s.get("kind") == "war" else n)
        services[n] = {"kind": s.get("kind", "jar"), "repo": s.get("repo"), "port": s.get("port"), "deployed": st.get(n),
                       "process": proc, "health": hs.get(n), "health_target": health_cfg[n],
                       "url": (health_cfg[n] or {}).get("url")}
    infra_list = {}
    for n in ("prometheus", "loki", "grafana", "postgres"):
        infra_list[n] = {"enabled": n in cfg["infra"]["enabled"], "process": procs.get(n)}
    return {"log_shipper": procs.get(compose.SHIPPER),
            "process_compose": {"installed": pc.available(), "up": bool(procs), "port": cfg["process_compose_port"]},
            "services": services, "stacks": cfg["stacks"], "infra": infra_list,
            "jboss": {"configured": bool(cfg["jboss"].get("home")), "process": procs.get(compose.JBOSS)}}


def doctor(cfg: dict) -> list:
    import subprocess
    out = []
    for tool, hint in (("git", "xcode-select --install"), ("process-compose", "brew install process-compose"),
                       ("docker", "install Docker Desktop / OrbStack / Colima (observability stack)"),
                       ("mvn", "brew install maven (or use ./mvnw)"), ("k6", "brew install k6 (load tests)")):
        out.append((tool, bool(shutil.which(tool)), hint))
    ws = config.path(cfg, "workspace")
    out.append((f"workspace {ws}", ws.is_dir(), "set `workspace` in config.json"))
    out.append(("repos found", bool(repos.discover(cfg)), "workspace must contain git repositories"))
    jh = cfg["jboss"].get("home")
    out.append(("jboss.home", (not jh) or (Path(jh).expanduser() / "bin/standalone.sh").exists(), "optional; needed for war services"))
    return out
